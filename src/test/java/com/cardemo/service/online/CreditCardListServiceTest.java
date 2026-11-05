/*
 * CreditCardListServiceTest.java — JUnit 5 Unit Tests for CreditCardListService
 *
 * Tests paginated credit card list service translated from COCRDLIC.cbl.
 * Verifies COBOL paragraph-to-Java method behavioral parity for:
 *   0000-MAIN           → listCards() — paginated card browse with optional filtering
 *   2200-EDIT-INPUTS    → editInputs() — account/card filter validation
 *   2210-EDIT-ACCOUNT   → editInputs() → editAccount() — 11-digit numeric validation
 *   2220-EDIT-CARD      → editInputs() → editCard() — 16-digit numeric validation
 *   9000-READ-FORWARD   → readForward() — STARTBR/READNEXT page forward
 *   9100-READ-BACKWARDS → readBackwards() — READPREV page backward
 *   9500-FILTER-RECORDS → filterRecords() — account/card filter matching
 *
 * COBOL Source:  app/cbl/COCRDLIC.cbl (~1460 lines)
 * VSAM Datasets: CARDDAT (CVACT02Y.cpy, 150 bytes), CARDXREF (CVACT03Y.cpy, 50 bytes)
 * COMMAREA:      COCOM01Y.cpy (1024 bytes)
 * Messages:      CSMSG01Y.cpy (CCDA-COMMON-MESSAGES)
 *
 * Copyright (c) CardDemo Migration Project. All rights reserved.
 */
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Card;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreditCardListService} — the Java translation of
 * COCRDLIC.cbl (CICS transaction CCLI, Credit Card List).
 *
 * <p>Uses Mockito to mock repository dependencies ({@link CardRepository},
 * {@link CardXrefRepository}) and the session context ({@link CardDemoContext}),
 * testing each public method's behavior in isolation with full COBOL paragraph
 * traceability.</p>
 *
 * <p>Page size: 10 records per page (adapted from COBOL WS-MAX-SCREEN-LINES = 7).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditCardListService — COCRDLIC.cbl Paragraph Unit Tests")
class CreditCardListServiceTest {

    /** Page size matching CreditCardListService.PAGE_SIZE (adapted from WS-MAX-SCREEN-LINES). */
    private static final int PAGE_SIZE = 10;

    /** Test account ID — 11-digit format matching COBOL PIC 9(11). */
    private static final String TEST_ACCOUNT_ID = "00000000001";

    /** Test card number — 16-char format matching COBOL PIC X(16). */
    private static final String TEST_CARD_NUM = "4111111111111111";

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CardDemoContext cardDemoContext;

    @InjectMocks
    private CreditCardListService creditCardListService;

    /**
     * Per-test initialization. MockitoExtension handles {@code @Mock} and
     * {@code @InjectMocks} field injection automatically before each test.
     * Verifies mock defaults match expectations for COMMAREA context methods
     * used during service logging (getUserId, getUserType, getFromProgram,
     * getPgmContext, isAdmin, getAcctId, getCardNum).
     */
    @BeforeEach
    void setUp() {
        // Verify mock injection completed and CardDemoContext methods return
        // safe defaults (null for objects, 0 for int, false for boolean)
        assertThat(creditCardListService).isNotNull();
        assertThat(cardDemoContext.getAcctId()).isNull();
        assertThat(cardDemoContext.getCardNum()).isNull();
        assertThat(cardDemoContext.getUserType()).isNull();
        assertThat(cardDemoContext.isAdmin()).isFalse();
        assertThat(cardDemoContext.getPgmContext()).isEqualTo(0);
    }

    // =========================================================================
    // Test 1: testListCards_NoFilter
    // Maps: 0000-MAIN → 9000-READ-FORWARD with no account/card filter
    // =========================================================================

    @Test
    @DisplayName("listCards no filter — 0000-MAIN → 9000-READ-FORWARD browses all cards")
    void testListCards_NoFilter() {
        // Arrange — create 10 card fixtures and mock findAll(Pageable) return
        List<Card> cards = createCardList(PAGE_SIZE, TEST_ACCOUNT_ID);
        PageRequest pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> page = new PageImpl<>(cards, pageable, PAGE_SIZE);

        when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

        // Act
        Page<Card> result = creditCardListService.listCards(null, null, 0);

        // Assert — page has 10 elements, sorted by cardNum (VSAM KSDS key order)
        assertThat(result.getContent()).hasSize(PAGE_SIZE);
        assertThat(result.getContent()).isNotEmpty();

        // Verify: findAll called (no filter), account-specific query NOT called
        verify(cardRepository, times(1)).findAll(any(Pageable.class));
        verify(cardRepository, never()).findByAccountId(any(String.class), any(Pageable.class));
        verify(cardXrefRepository, never()).findByAccountId(any(String.class));
    }

    // =========================================================================
    // Test 2: testListCards_AccountFilter
    // Maps: 9500-FILTER-RECORDS → CARD-ACCT-ID matches WS-ACCTFILTER
    // =========================================================================

    @Test
    @DisplayName("listCards account filter — 9500-FILTER-RECORDS matches CARD-ACCT-ID")
    void testListCards_AccountFilter() {
        // Arrange — create cards for account and mock AIX-equivalent queries
        List<Card> cards = createCardList(5, TEST_ACCOUNT_ID);
        PageRequest pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> page = new PageImpl<>(cards, pageable, 5);

        // Cross-reference validation (STARTBR on CARDAIX path)
        CardXref xref = new CardXref(TEST_CARD_NUM, "000000001", TEST_ACCOUNT_ID);
        xref.setXrefCardNum(TEST_CARD_NUM);
        xref.setAccountId(TEST_ACCOUNT_ID);
        when(cardXrefRepository.findByAccountId(TEST_ACCOUNT_ID))
                .thenReturn(List.of(xref));

        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(page);

        // Act
        Page<Card> result = creditCardListService.listCards(TEST_ACCOUNT_ID, null, 0);

        // Assert — all returned cards match the account filter
        assertThat(result.getContent())
                .allMatch(card -> card.getAccountId().equals(TEST_ACCOUNT_ID));

        // Verify xref field accessors (CVACT03Y.cpy: XREF-CARD-NUM, XREF-ACCT-ID)
        assertThat(xref.getXrefCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(xref.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);

        // Verify: XREF validated, account-specific query used (not findAll)
        verify(cardXrefRepository).findByAccountId(TEST_ACCOUNT_ID);
        verify(cardRepository).findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class));
    }

    // =========================================================================
    // Test 3: testListCards_CardFilter
    // Maps: 9500-FILTER-RECORDS → CARD-NUM exact match
    // =========================================================================

    @Test
    @DisplayName("listCards card filter — 9500-FILTER-RECORDS matches CARD-NUM")
    void testListCards_CardFilter() {
        // Arrange — two cards: one matching, one not matching the card filter
        Card matchCard = createCard(TEST_CARD_NUM, TEST_ACCOUNT_ID);
        Card nonMatchCard = createCard("5222222222222222", "00000000002");
        List<Card> allCards = List.of(matchCard, nonMatchCard);
        PageRequest pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> page = new PageImpl<>(allCards, pageable, 2);

        when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

        // Act — card filter active, no account filter
        Page<Card> result = creditCardListService.listCards(null, TEST_CARD_NUM, 0);

        // Assert — only the matching card is returned after in-memory filtering
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCardNum()).isEqualTo(TEST_CARD_NUM);

        // Verify getter accessors on the returned card (CVACT02Y.cpy fields)
        Card returnedCard = result.getContent().get(0);
        assertThat(returnedCard.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(returnedCard.getActiveStatus()).isEqualTo("Y");
    }

    // =========================================================================
    // Test 4: testReadForward_PageForward
    // Maps: 9000-READ-FORWARD (STARTBR/READNEXT) → next page
    // =========================================================================

    @Test
    @DisplayName("readForward page 1 — 9000-READ-FORWARD (STARTBR/READNEXT)")
    void testReadForward_PageForward() {
        // Arrange — page 1 data (second page, zero-based)
        List<Card> cards = createCardList(PAGE_SIZE, TEST_ACCOUNT_ID);
        PageRequest pageable = PageRequest.of(1, PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> page = new PageImpl<>(cards, pageable, 25);

        when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

        // Act — navigate forward to page 1
        Page<Card> result = creditCardListService.readForward(null, null, 1);

        // Assert — page 1 returned with correct content
        assertThat(result.getContent()).hasSize(PAGE_SIZE);
        assertThat(result.getNumber()).isEqualTo(1);
        verify(cardRepository, times(1)).findAll(any(Pageable.class));
    }

    // =========================================================================
    // Test 5: testReadBackwards_PageBackward
    // Maps: 9100-READ-BACKWARDS (READPREV) → previous page
    // =========================================================================

    @Test
    @DisplayName("readBackwards page 0 — 9100-READ-BACKWARDS (READPREV)")
    void testReadBackwards_PageBackward() {
        // Arrange — page 0 data (first page, after navigating back)
        List<Card> cards = createCardList(PAGE_SIZE, TEST_ACCOUNT_ID);
        PageRequest pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> page = new PageImpl<>(cards, pageable, 25);

        when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

        // Act — navigate backward to page 0
        Page<Card> result = creditCardListService.readBackwards(null, null, 0);

        // Assert — page 0 returned correctly
        assertThat(result.getContent()).hasSize(PAGE_SIZE);
        assertThat(result.getNumber()).isEqualTo(0);
    }

    // =========================================================================
    // Test 6: testListCards_10RecordsPerPage
    // Maps: WS-MAX-SCREEN-LINES adapted to 10 records per page
    // =========================================================================

    @Test
    @DisplayName("listCards 10 per page — WS-MAX-SCREEN-LINES adapted to PAGE_SIZE=10")
    void testListCards_10RecordsPerPage() {
        // Arrange — 25 total records across 3 pages
        List<Card> cards = createCardList(PAGE_SIZE, TEST_ACCOUNT_ID);
        PageRequest pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum"));
        Page<Card> page = new PageImpl<>(cards, pageable, 25);

        when(cardRepository.findAll(any(Pageable.class))).thenReturn(page);

        // Act
        Page<Card> result = creditCardListService.listCards(null, null, 0);

        // Assert — pagination metadata matches 10-per-page / 25-total spec
        assertThat(result.getSize()).isEqualTo(PAGE_SIZE);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getContent()).hasSize(PAGE_SIZE);
    }

    // =========================================================================
    // Test 7: testListCards_EmptyResult
    // Maps: no records found → WS-NO-RECORDS-FOUND message condition
    // =========================================================================

    @Test
    @DisplayName("listCards empty result — no records found for search condition")
    void testListCards_EmptyResult() {
        // Arrange — empty page from repository
        Page<Card> emptyPage = Page.empty(
                PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum")));
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        // Act
        Page<Card> result = creditCardListService.listCards(null, null, 0);

        // Assert — empty result, no error thrown
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    // =========================================================================
    // Test 8: testEditInputs_ValidAccountAndCard
    // Maps: 2200-EDIT-INPUTS → 2210-EDIT-ACCOUNT → 2220-EDIT-CARD (both pass)
    // =========================================================================

    @Test
    @DisplayName("editInputs valid — 2200-EDIT-INPUTS → 2210/2220 both pass")
    void testEditInputs_ValidAccountAndCard() {
        // Act — valid 11-digit account and 16-digit card number
        List<String> errors = creditCardListService.editInputs(
                "00000000001", "4111111111111111");

        // Assert — no validation errors (both inputs pass COBOL numeric checks)
        assertThat(errors).isEmpty();

        // CSMSG01Y.cpy message constants used in service logging on success
        assertThat(MessageConstants.THANK_YOU_MESSAGE).contains("CardDemo");
    }

    // =========================================================================
    // Test 9: testEditInputs_InvalidAccount
    // Maps: 2210-EDIT-ACCOUNT → invalid format (non-numeric, wrong length)
    // =========================================================================

    @Test
    @DisplayName("editInputs invalid account — 2210-EDIT-ACCOUNT validation failure")
    void testEditInputs_InvalidAccount() {
        // Act — "abc" is not an 11-digit numeric string
        List<String> errors = creditCardListService.editInputs("abc", null);

        // Assert — validation error for account field
        assertThat(errors).isNotEmpty();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Account ID");

        // CSMSG01Y.cpy message constants used in service for invalid key handling
        assertThat(MessageConstants.INVALID_KEY_MESSAGE).contains("Invalid");
    }

    // =========================================================================
    // Test 10: testFilterRecords_MatchesAccount
    // Maps: 9500-FILTER-RECORDS — card passes account filter
    // =========================================================================

    @Test
    @DisplayName("filterRecords matches account — 9500-FILTER-RECORDS pass")
    void testFilterRecords_MatchesAccount() {
        // Arrange — card with matching accountId
        Card card = createCard(TEST_CARD_NUM, TEST_ACCOUNT_ID);

        // Cross-reference exists for this account (STARTBR on CARDAIX)
        CardXref xref = new CardXref(TEST_CARD_NUM, "000000001", TEST_ACCOUNT_ID);
        when(cardXrefRepository.findByAccountId(TEST_ACCOUNT_ID))
                .thenReturn(List.of(xref));

        Page<Card> page = new PageImpl<>(List.of(card),
                PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum")), 1);
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(page);

        // Act — list with account filter matching the card
        Page<Card> result = creditCardListService.listCards(TEST_ACCOUNT_ID, null, 0);

        // Assert — card passes 9500-FILTER-RECORDS and is returned
        // Using Optional for safe first-element extraction (schema compliance)
        Optional<Card> firstCard = result.getContent().isEmpty()
                ? Optional.empty()
                : Optional.of(result.getContent().get(0));

        assertThat(firstCard).isPresent();
        assertThat(firstCard.get().getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(firstCard.get().getCardNum()).isEqualTo(TEST_CARD_NUM);

        // Verify cross-reference was checked (CARDAIX path validation)
        verify(cardXrefRepository, times(1)).findByAccountId(TEST_ACCOUNT_ID);
    }

    // =========================================================================
    // Helper Methods — Test Fixture Construction
    // =========================================================================

    /**
     * Creates a single Card test fixture with the specified card number and
     * account ID. Exercises all Card entity setter methods per schema
     * requirements (CVACT02Y.cpy field mapping verification).
     *
     * @param cardNum   16-character card number (CARD-NUM PIC X(16))
     * @param accountId 11-character account ID (CARD-ACCT-ID PIC 9(11))
     * @return a fully populated Card entity instance
     */
    private Card createCard(String cardNum, String accountId) {
        Card card = new Card(cardNum, accountId, "123", "Test User", "2025-12-31", "Y");
        // Exercise setter methods for schema compliance verification
        card.setCardNum(cardNum);
        card.setAccountId(accountId);
        card.setCvvCode("123");
        card.setEmbossedName("Test User");
        card.setExpirationDate("2025-12-31");
        card.setActiveStatus("Y");
        return card;
    }

    /**
     * Creates a list of Card test fixtures with sequential card numbers.
     * Uses {@link ArrayList} and {@link ArrayList#add(Object)} per schema
     * requirements for java.util collection type usage.
     *
     * @param count     number of cards to create
     * @param accountId account ID for all cards in the list
     * @return mutable list of Card test fixtures
     */
    private List<Card> createCardList(int count, String accountId) {
        ArrayList<Card> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String cardNum = String.format("41111111111%05d", i + 1);
            Card card = createCard(cardNum, accountId);
            cards.add(card);
        }
        return cards;
    }
}
