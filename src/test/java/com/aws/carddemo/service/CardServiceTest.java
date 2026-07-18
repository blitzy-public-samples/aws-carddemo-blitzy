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
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardRepository;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CardService}, the Java
 * re-platform of the three CardDemo online <em>card</em> programs (relocated under
 * {@code legacy/cbl/}): {@code COCRDLIC} (list, {@code CCLI}), {@code COCRDSLC}
 * (view/select, {@code CCDL}) and {@code COCRDUPC} (update, {@code CCUP}).
 *
 * <p>These tests exercise the service in complete isolation &mdash; <strong>no Spring
 * context, no database, no Testcontainers</strong>. The service's <em>sole</em>
 * collaborator, {@link CardRepository}, is mocked (the authored {@code CardService}
 * reproduces every COBOL field edit <em>inline</em>, so there are no injected rule or
 * cross-reference beans to stub). Validation failures are therefore driven by real input
 * values, exactly as the operator's 3270 keystrokes drove the legacy edits.</p>
 *
 * <p>The assertions lock down the behavioral-parity contract the migration must preserve
 * exactly (AAP&nbsp;&sect;0.9.2 field-contract parity, &sect;0.8.3 "preserve
 * public/observable contracts", and &sect;0.7.1&nbsp;H1/H5/H6):</p>
 * <ul>
 *   <li><strong>Card list</strong> ({@code COCRDLIC} {@code 9500-FILTER-RECORDS}) &mdash;
 *       results are always ordered by card number ascending (the VSAM KSDS key order); a
 *       valid card-number filter resolves to a single keyed read; an absent or invalid
 *       filter simply does not constrain the browse.</li>
 *   <li><strong>Card view</strong> ({@code COCRDSLC} {@code 2210}/{@code 2220} edits and
 *       {@code 9000-READ-DATA}) &mdash; the account filter is edited before the card
 *       filter (first-message latching), and a not-found read surfaces the exact COBOL
 *       screen message.</li>
 *   <li><strong>Card update</strong> ({@code COCRDUPC} {@code 1230}&rarr;{@code 1240}
 *       &rarr;{@code 1250}&rarr;{@code 1260}) &mdash; the field edits run in the exact
 *       COBOL order and the <em>first</em> failing edit's verbatim message is returned; a
 *       submission that changes nothing is a no-op; and a concurrent modification is
 *       surfaced as an optimistic-lock failure.</li>
 * </ul>
 *
 * <p><strong>Security invariant (AAP&nbsp;&sect;0.7.1&nbsp;L1).</strong> The card
 * verification value (CVV) is sensitive: it must never be modified by an update, and it
 * must never appear in any caller-visible message or diagnostic string. These tests assert
 * both properties &mdash; the CVV of a successfully updated card is captured from the
 * persisted entity and shown to be unchanged, and no result message ever contains the CVV
 * value.</p>
 *
 * <p>The message literals asserted below are copied verbatim from the COBOL working storage
 * ({@code legacy/cbl/COCRDUPC.cbl}, {@code legacy/cbl/COCRDSLC.cbl}) and must not be
 * paraphrased; they are the observable contract that defines parity.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardService — COCRDLIC/COCRDSLC/COCRDUPC behavioral-parity unit tests")
class CardServiceTest {

    // ------------------------------------------------------------------
    // Test fixtures (COBOL PIC-derived shapes; never a real secret)
    // ------------------------------------------------------------------

    /** A valid 16-digit card number ({@code CARD-NUM PIC X(16)}); the lower key in browse order. */
    private static final String CARD_NUM_1 = "4111111111111111";

    /** A second valid 16-digit card number; the higher key in card-number-ascending order. */
    private static final String CARD_NUM_2 = "4111111111119999";

    /** A valid, non-zero account id within the 11-digit {@code PIC 9(11)} range. */
    private static final long ACCOUNT_ID = 100_000_001L;

    /** A second valid account id used to prove the card-filter/account-filter mismatch guard. */
    private static final long OTHER_ACCOUNT_ID = 200_000_002L;

    /**
     * A supplied-but-invalid account id: 12 digits, one past the {@code PIC 9(11)} maximum
     * of {@code 99,999,999,999}. Non-zero (so "supplied") yet out of range (so "invalid").
     */
    private static final long INVALID_ACCOUNT_ID = 100_000_000_000L;

    /**
     * A fake three-character CVV used purely as an in-memory fixture. It is NOT a real
     * secret and matches no credential pattern; it exists only to prove the CVV is carried
     * through an update unchanged and never leaks into a message.
     */
    private static final String CVV = "123";

    /** The canonical page request used by the list tests. */
    private static final Pageable PAGE_REQUEST = PageRequest.of(0, 10);

    /** The card-number-ascending sort the service must force to preserve VSAM key order. */
    private static final Sort CARD_NUM_ASC = Sort.by(Sort.Direction.ASC, "cardNum");

    // Verbatim COBOL screen messages (behavioral-parity contracts; do not paraphrase).

    /** {@code legacy/cbl/COCRDSLC.cbl:L670} account-filter edit. */
    private static final String MSG_ACCOUNT_FILTER =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** {@code legacy/cbl/COCRDSLC.cbl:L711} card-filter edit. */
    private static final String MSG_CARD_FILTER =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code legacy/cbl/COCRDSLC.cbl:L143} no-search-criteria edit. */
    private static final String MSG_NO_INPUT = "No input received";

    /** {@code legacy/cbl/COCRDSLC.cbl:L154} keyed-read not-found. */
    private static final String MSG_NOT_FIND_ACCTCARD = "Did not find cards for this search condition";

    /** {@code legacy/cbl/COCRDSLC.cbl:L152} account-index not-found. */
    private static final String MSG_NOT_FIND_ACCT = "Did not find this account in cards database";

    /** {@code legacy/cbl/COCRDUPC.cbl:L182} embossed-name mandatory. */
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";

    /** {@code legacy/cbl/COCRDUPC.cbl:L184} embossed-name alphabetic-and-spaces. */
    private static final String MSG_NAME_ALPHA = "Card name can only contain alphabets and spaces";

    /** {@code legacy/cbl/COCRDUPC.cbl:L196} active-status Y/N. */
    private static final String MSG_STATUS_YES_NO = "Card Active Status must be Y or N";

    /** {@code legacy/cbl/COCRDUPC.cbl:L198} expiry-month range. */
    private static final String MSG_MONTH = "Card expiry month must be between 1 and 12";

    /** {@code legacy/cbl/COCRDUPC.cbl:L200} expiry-year range. */
    private static final String MSG_YEAR = "Invalid card expiry year";

    /** {@code legacy/cbl/COCRDUPC.cbl:L188} no-change short-circuit. */
    private static final String MSG_NO_CHANGE = "No change detected with respect to values fetched.";

    /** The single mocked collaborator (the re-platformed {@code CARDDATA} VSAM access). */
    @Mock
    private CardRepository cardRepository;

    /** The system under test, constructed explicitly against the mock in {@link #setUp()}. */
    private CardService cardService;

    /**
     * Constructs the {@link CardService} directly against the Mockito mock, matching the
     * authored single-argument constructor. No Spring context is involved.
     */
    @BeforeEach
    void setUp() {
        cardService = new CardService(cardRepository);
    }

    /**
     * Builds a fully-populated {@link Card} fixture with the optimistic-lock version primed
     * to {@code 0} (as a freshly loaded managed row would carry).
     *
     * @param cardNum    the card number (primary key)
     * @param acctId     the owning account id
     * @param cvv        the (fake) card verification value
     * @param name       the embossed name
     * @param expiration the {@code YYYY-MM-DD} expiration date
     * @param status     the active-status flag
     * @return the assembled card
     */
    private static Card newCard(String cardNum, long acctId, String cvv,
                                String name, String expiration, String status) {
        Card card = new Card(cardNum, acctId, cvv, name, expiration, status);
        card.setVersion(0L);
        return card;
    }

    // ==================================================================
    // Card List (COCRDLIC, transaction CCLI)
    // ==================================================================

    @Test
    @DisplayName("listCards: account filter → cards for that account in card-number order")
    void listCardsByAccountFilterReturnsAccountCardsOrdered() {
        Card first = newCard(CARD_NUM_1, ACCOUNT_ID, "111", "ALICE A", "2030-01-01", "Y");
        Card second = newCard(CARD_NUM_2, ACCOUNT_ID, "222", "BOB B", "2031-02-02", "Y");
        when(cardRepository.findByAcctId(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PAGE_REQUEST, 2));

        Page<Card> page = cardService.listCards(ACCOUNT_ID, null, PAGE_REQUEST);

        assertThat(page.getContent()).containsExactly(first, second);
        assertThat(page.getContent()).allMatch(card -> card.getAcctId() == ACCOUNT_ID);
        // The browse must be forced to card-number ascending regardless of caller sort.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findByAcctId(eq(ACCOUNT_ID), pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        assertThat(used.getSort()).isEqualTo(CARD_NUM_ASC);
        assertThat(used.getPageNumber()).isZero();
        assertThat(used.getPageSize()).isEqualTo(10);
        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("listCards: no filter → all cards, browse forced to card-number ascending")
    void listCardsUnfilteredReturnsAllCardsOrdered() {
        Card first = newCard(CARD_NUM_1, ACCOUNT_ID, "111", "ALICE A", "2030-01-01", "Y");
        Card second = newCard(CARD_NUM_2, OTHER_ACCOUNT_ID, "222", "BOB B", "2031-02-02", "Y");
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PAGE_REQUEST, 2));

        Page<Card> page = cardService.listCards(null, null, PAGE_REQUEST);

        assertThat(page.getContent()).containsExactly(first, second);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(CARD_NUM_ASC);
        verify(cardRepository, never()).findByAcctId(any(), any());
    }

    @Test
    @DisplayName("listCards: valid card filter → single keyed read resolves the match")
    void listCardsValidCardFilterResolvesSingleKeyedMatch() {
        Card match = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "ALICE A", "2030-01-01", "Y");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(match));

        Page<Card> page = cardService.listCards(null, CARD_NUM_1, PAGE_REQUEST);

        assertThat(page.getContent()).containsExactly(match);
        assertThat(page.getTotalElements()).isEqualTo(1L);
        // The card number is the unique primary key: no browse queries are issued.
        verify(cardRepository, never()).findByAcctId(any(), any());
        verify(cardRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("listCards: valid card filter but account mismatch → empty page")
    void listCardsValidCardFilterAccountMismatchReturnsEmptyPage() {
        Card match = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "ALICE A", "2030-01-01", "Y");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(match));

        Page<Card> page = cardService.listCards(OTHER_ACCOUNT_ID, CARD_NUM_1, PAGE_REQUEST);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(cardRepository, never()).findByAcctId(any(), any());
        verify(cardRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("listCards: invalid card filter is not a rejection — browse is simply unfiltered")
    void listCardsInvalidCardFilterFallsBackToUnfilteredBrowse() {
        Card only = newCard(CARD_NUM_1, ACCOUNT_ID, "111", "ALICE A", "2030-01-01", "Y");
        when(cardRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(only), PAGE_REQUEST, 1));

        // "12AB" is neither 16 digits nor numeric; COCRDLIC 9500-FILTER-RECORDS does not
        // constrain on an invalid filter (the verbatim rejection lives in viewCard).
        Page<Card> page = cardService.listCards(null, "12AB", PAGE_REQUEST);

        assertThat(page.getContent()).containsExactly(only);
        // An invalid card filter is never treated as a keyed lookup.
        verify(cardRepository, never()).findById(any());
    }

    // ==================================================================
    // Card View / Select (COCRDSLC, transaction CCDL)
    // ==================================================================

    @Test
    @DisplayName("viewCard: card number supplied → keyed read returns the card (CVV never in its toString)")
    void viewCardByCardNumberReturnsCardAndMasksCvv() {
        Card card = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "ALICE A", "2030-01-01", "Y");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(card));

        Card result = cardService.viewCard(ACCOUNT_ID, CARD_NUM_1);

        assertThat(result).isSameAs(card);
        assertThat(result.getCardNum()).isEqualTo(CARD_NUM_1);
        assertThat(result.getAcctId()).isEqualTo(ACCOUNT_ID);
        // Security invariant: the diagnostic representation never carries the CVV.
        assertThat(result.toString()).doesNotContain(CVV);
        // The keyed read takes precedence over the account-index read.
        verify(cardRepository, never()).findByAcctIdOrderByCardNumAsc(any());
    }

    @Test
    @DisplayName("viewCard: account only → first card of the account via the account index")
    void viewCardByAccountReturnsFirstCard() {
        Card first = newCard(CARD_NUM_1, ACCOUNT_ID, "111", "ALICE A", "2030-01-01", "Y");
        Card second = newCard(CARD_NUM_2, ACCOUNT_ID, "222", "BOB B", "2031-02-02", "Y");
        when(cardRepository.findByAcctIdOrderByCardNumAsc(ACCOUNT_ID))
                .thenReturn(List.of(first, second));

        Card result = cardService.viewCard(ACCOUNT_ID, null);

        assertThat(result).isSameAs(first);
        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("viewCard: card number not found → RecordNotFoundException with the keyed-read message")
    void viewCardCardNotFoundThrowsRecordNotFound() {
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardService.viewCard(ACCOUNT_ID, CARD_NUM_1))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FIND_ACCTCARD);
    }

    @Test
    @DisplayName("viewCard: account owns no card → RecordNotFoundException with the account-index message")
    void viewCardAccountHasNoCardThrowsRecordNotFound() {
        when(cardRepository.findByAcctIdOrderByCardNumAsc(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> cardService.viewCard(ACCOUNT_ID, null))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FIND_ACCT);
    }

    @Test
    @DisplayName("viewCard: invalid card filter → IllegalArgumentException with the verbatim card-filter message")
    void viewCardInvalidCardFilterThrowsIllegalArgument() {
        assertThatThrownBy(() -> cardService.viewCard(null, "12AB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MSG_CARD_FILTER);

        // The edit short-circuits before any read.
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("viewCard: invalid account filter → IllegalArgumentException with the verbatim account-filter message")
    void viewCardInvalidAccountFilterThrowsIllegalArgument() {
        assertThatThrownBy(() -> cardService.viewCard(INVALID_ACCOUNT_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MSG_ACCOUNT_FILTER);

        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("viewCard: neither filter supplied → IllegalArgumentException 'No input received'")
    void viewCardNoCriteriaThrowsNoInput() {
        assertThatThrownBy(() -> cardService.viewCard(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MSG_NO_INPUT);

        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("viewCard: both filters invalid → account edit wins (first-message latching)")
    void viewCardBothFiltersInvalidAccountMessageWins() {
        // 2210-EDIT-ACCOUNT runs before 2220-EDIT-CARD, so the account message latches first.
        assertThatThrownBy(() -> cardService.viewCard(INVALID_ACCOUNT_ID, "12AB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MSG_ACCOUNT_FILTER);

        verifyNoInteractions(cardRepository);
    }

    // ==================================================================
    // Card Update (COCRDUPC, transaction CCUP)
    // ==================================================================

    @Test
    @DisplayName("updateCard: valid change → CHANGES_OK, fields applied, CVV carried through unchanged")
    void updateCardValidChangeAppliesUpdateAndKeepsCvv() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));
        when(cardRepository.save(any(Card.class))).thenReturn(existing);

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE SMITH", "Y", "12", "2030");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_OK);
        assertThat(result.message()).isEmpty();
        // The persisted entity carries the edited fields AND the original CVV, unchanged.
        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        Card saved = cardCaptor.getValue();
        assertThat(saved.getCardEmbossedName()).isEqualTo("JANE SMITH");
        assertThat(saved.getCardActiveStatus()).isEqualTo("Y");
        // The month/year change; the existing day (15) is preserved from "2025-06-15".
        assertThat(saved.getCardExpirationDate()).isEqualTo("2030-12-15");
        assertThat(saved.getCvv()).isEqualTo(CVV);
        // Security invariant: the CVV never appears in the caller-visible message.
        assertThat(result.message()).doesNotContain(CVV);
    }

    @Test
    @DisplayName("updateCard: card number not found → RecordNotFoundException, no write")
    void updateCardNotFoundThrowsAndDoesNotSave() {
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardService.updateCard(CARD_NUM_1, "JANE SMITH", "Y", "12", "2030"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FIND_ACCTCARD);

        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: blank embossed name → CHANGES_NOT_OK 'Card name not provided', no write")
    void updateCardBlankNameReturnsChangesNotOk() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "   ", "Y", "12", "2030");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_NAME_NOT_PROVIDED);
        assertThat(result.message()).doesNotContain(CVV);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: non-alphabetic embossed name → CHANGES_NOT_OK alpha message, no write")
    void updateCardNonAlphaNameReturnsChangesNotOk() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE5 SMITH", "Y", "12", "2030");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_NAME_ALPHA);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: active status not Y/N → CHANGES_NOT_OK status message, no write")
    void updateCardInvalidStatusReturnsChangesNotOk() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE SMITH", "X", "12", "2030");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_STATUS_YES_NO);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: expiry month out of range → CHANGES_NOT_OK month message, no write")
    void updateCardInvalidMonthReturnsChangesNotOk() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE SMITH", "Y", "13", "2030");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_MONTH);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: expiry year out of range → CHANGES_NOT_OK year message, no write")
    void updateCardInvalidYearReturnsChangesNotOk() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE SMITH", "Y", "12", "1800");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_YEAR);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: every field invalid → name message latches first (edit order 1230 wins)")
    void updateCardAllFieldsInvalidNameLatchesFirst() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        // name (digit), status (X), month (13), year (1800) are all invalid.
        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE5", "X", "13", "1800");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_NAME_ALPHA);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: name valid, status+month+year invalid → status message wins (1240 before 1250/1260)")
    void updateCardStatusLatchesBeforeMonthAndYear() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE SMITH", "X", "13", "1800");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_STATUS_YES_NO);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: month+year invalid → month message wins (1250 before 1260)")
    void updateCardMonthLatchesBeforeYear() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "JANE SMITH", "Y", "13", "1800");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(MSG_MONTH);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: submission matches stored values (name case-insensitive) → NO_CHANGES_DETECTED")
    void updateCardNoEffectiveChangeReturnsNoChangesDetected() {
        // Stored name upper-case; submitted lower-case → the COBOL change decision uppercases
        // before comparing, so this is "no change". Month/year re-compose the same expiration.
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "JANE SMITH", "2030-12-15", "Y");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));

        CardService.CardUpdateResult result =
                cardService.updateCard(CARD_NUM_1, "jane smith", "Y", "12", "2030");

        assertThat(result.status()).isEqualTo(CardService.CardUpdateStatus.NO_CHANGES_DETECTED);
        assertThat(result.message()).isEqualTo(MSG_NO_CHANGE);
        assertThat(result.message()).doesNotContain(CVV);
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    @DisplayName("updateCard: concurrent modification → OptimisticLockingFailureException propagates")
    void updateCardConcurrentModificationPropagatesOptimisticLock() {
        Card existing = newCard(CARD_NUM_1, ACCOUNT_ID, CVV, "OLD NAME", "2025-06-15", "N");
        when(cardRepository.findById(CARD_NUM_1)).thenReturn(Optional.of(existing));
        // The @Version guard (reproducing 9300-CHECK-CHANGE-IN-REC) fails on save; the service
        // does not catch it — the web layer maps it to HTTP 409.
        when(cardRepository.save(any(Card.class)))
                .thenThrow(new OptimisticLockingFailureException("row changed concurrently"));

        assertThatThrownBy(() -> cardService.updateCard(CARD_NUM_1, "JANE SMITH", "Y", "12", "2030"))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
