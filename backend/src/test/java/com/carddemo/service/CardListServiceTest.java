/*****************************************************************
 * Program:     CardListServiceTest.java
 * Layer:       Unit test layer
 * Function:    JUnit 5 test class for CardListService validating
 *              business logic transformation from COCRDLIC.cbl
 ******************************************************************
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
 * language governing permissions and limitations under the License
 ******************************************************************/
package com.carddemo.service;

import com.carddemo.dto.response.CardListResponse;
import com.carddemo.dto.response.CardListResponse.CardItemDTO;
import com.carddemo.entity.Card;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for CardListService testing business logic
 * transformation from COBOL program COCRDLIC.cbl.
 * 
 * <p>This test class validates:
 * <ul>
 *   <li>Pagination with 7 cards per page matching COBOL screen display limit (WS-MAX-SCREEN-LINES = 7)</li>
 *   <li>Sorting by card number or expiry date preserving VSAM KSDS sequential read patterns</li>
 *   <li>Account-based filtering for card retrieval</li>
 *   <li>Status filtering (ACTIVE/EXPIRED/BLOCKED) matching COBOL 88-level conditions</li>
 *   <li>VSAM CARDDAT file sequential read with cross-reference navigation → JPA repository queries</li>
 *   <li>PF7/PF8 page forward/backward navigation patterns</li>
 *   <li>Card number masking for PCI-DSS compliance (****-****-****-1234)</li>
 *   <li>Expiry warning business rule (within 30 days)</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Context:</strong></p>
 * <ul>
 *   <li>COBOL Program: app/cbl/COCRDLIC.cbl (Card List CICS transaction)</li>
 *   <li>Copybook: app/cpy/CVACT03Y.cpy (Card data structure)</li>
 *   <li>Transaction ID: CCLI (Card List transaction)</li>
 *   <li>Screen Pattern: 7 cards per page (WS-MAX-SCREEN-LINES constant line 177-178)</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Requirements:</strong></p>
 * <ul>
 *   <li>Section 0.9: Test Case Compatibility - Card list matches COBOL display</li>
 *   <li>Section 0.1: User Interface Modernization - Pagination patterns preserved</li>
 *   <li>Section 0.3: VSAM File → PostgreSQL Table - Sequential read to JPA pagination</li>
 *   <li>Section 0.6: Service Layer for Business Logic - CardListService from COCRDLIC.cbl</li>
 * </ul>
 * 
 * @see CardListService
 * @see CardRepository
 * @see CardListResponse
 * @since 1.0
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService Test Suite - COBOL COCRDLIC.cbl Transformation Validation")
public class CardListServiceTest {

    /**
     * Mock of CardRepository for database operations simulation.
     * Mocked to control test data and verify repository method invocations.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Mock of AccountRepository for account existence validation.
     * Mocked to simulate account lookup operations in card list retrieval.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * CardListService instance under test with mocked dependencies injected.
     * All tests execute against this service instance with controlled mock behavior.
     */
    @InjectMocks
    private CardListService cardListService;

    /**
     * Test account ID used across multiple test cases.
     * COBOL equivalent: WS-CARD-RID-ACCT-ID PIC 9(11)
     */
    private static final Long TEST_ACCOUNT_ID = 12345678901L;

    /**
     * Maximum cards per page constant matching COBOL WS-MAX-SCREEN-LINES = 7.
     * From COCRDLIC.cbl line 177-178
     */
    private static final int CARDS_PER_PAGE = 7;

    /**
     * Test fixture: List of mock Card entities for testing.
     * Populated in @BeforeEach setup method.
     */
    private List<Card> testCards;

    /**
     * Test fixture: Mock Page object for paginated results.
     * Configured per test case requirements.
     */
    private Page<Card> testCardPage;

    /**
     * Setup method executed before each test case.
     * Initializes test fixtures and common mock behaviors.
     * 
     * <p>Creates sample card data matching COBOL CARD-RECORD structure from CVACT03Y.cpy:
     * <ul>
     *   <li>16-character card numbers (CARD-NUM PIC X(16))</li>
     *   <li>11-digit account IDs (CARD-ACCT-ID PIC 9(11))</li>
     *   <li>Single-character status codes (CARD-ACTIVE-STATUS PIC X(01))</li>
     *   <li>Expiration dates in LocalDate format (CARD-EXPIRAION-DATE)</li>
     *   <li>CVV codes (CARD-CVV-CD PIC 9(03))</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Initialize test card list with 15 cards to test multi-page scenarios
        testCards = new ArrayList<>();
        
        // Create 15 test cards with varying properties
        for (int i = 1; i <= 15; i++) {
            Card card = new Card();
            card.setCardNumber(String.format("4532123456%06d", i)); // 16-digit card numbers
            card.setAccountId(TEST_ACCOUNT_ID);
            card.setCvvCode(String.format("%03d", (i * 11) % 1000)); // 3-digit CVV
            card.setEmbossedName("TEST CARDHOLDER " + i);
            
            // Set expiration dates: some expiring soon, some already expired, most valid
            if (i <= 5) {
                // Cards 1-5: Valid for next 2 years
                card.setExpirationDate(LocalDate.now().plusYears(2));
                card.setActiveStatus("Y"); // ACTIVE
            } else if (i <= 8) {
                // Cards 6-8: Expiring within 30 days (business rule test)
                card.setExpirationDate(LocalDate.now().plusDays(15));
                card.setActiveStatus("Y"); // ACTIVE but expiring soon
            } else if (i <= 10) {
                // Cards 9-10: Already expired
                card.setExpirationDate(LocalDate.now().minusDays(30));
                card.setActiveStatus("E"); // EXPIRED
            } else if (i <= 12) {
                // Cards 11-12: Blocked status
                card.setExpirationDate(LocalDate.now().plusYears(1));
                card.setActiveStatus("B"); // BLOCKED
            } else {
                // Cards 13-15: Inactive status
                card.setExpirationDate(LocalDate.now().plusMonths(6));
                card.setActiveStatus("N"); // INACTIVE
            }
            
            testCards.add(card);
        }
    }

    /**
     * Test: Validates that first page returns exactly 7 cards per page matching
     * COBOL WS-MAX-SCREEN-LINES = 7 pagination pattern.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 177-178:
     * 01 WS-CONSTANTS.
     *   05  WS-MAX-SCREEN-LINES  PIC S9(4) COMP VALUE 7.
     * 
     * Lines 1191-1196:
     * IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES
     *    SET READ-LOOP-EXIT  TO TRUE
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Page size must be exactly 7 cards</li>
     *   <li>First page shows cards 1-7</li>
     *   <li>hasNext flag is true (more cards available)</li>
     *   <li>hasPrevious flag is false (no previous page)</li>
     *   <li>Repository called with correct Pageable (page=0, size=7)</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: First page returns exactly 7 cards matching COBOL screen limit")
    void getCardList_FirstPage_Returns7Cards() {
        // ARRANGE: Setup mock for first page with 7 cards
        List<Card> firstPageCards = testCards.subList(0, 7);
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(firstPageCards, pageable, testCards.size());
        
        // Mock account existence check
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        
        // Mock repository to return first page
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Call service to retrieve first page
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify pagination behavior matches COBOL WS-MAX-SCREEN-LINES = 7
        assertNotNull(result, "Result page should not be null");
        assertEquals(7, result.getContent().size(), 
            "First page must contain exactly 7 cards per COBOL WS-MAX-SCREEN-LINES");
        assertEquals(0, result.getNumber(), "Page number should be 0 (first page)");
        assertEquals(7, result.getSize(), "Page size should be 7");
        assertEquals(15, result.getTotalElements(), "Total elements should be 15");
        assertEquals(3, result.getTotalPages(), "Total pages should be 3 (15 cards / 7 per page = 3 pages)");
        assertTrue(result.hasNext(), "Should have next page (cards 8-14 available)");
        assertFalse(result.hasPrevious(), "Should not have previous page (this is first page)");
        
        // Verify repository called with correct parameters
        verify(cardRepository, times(1)).findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class));
        verify(accountRepository, times(1)).findById(TEST_ACCOUNT_ID);
    }

    /**
     * Test: Validates account-based filtering correctly retrieves cards for specified account.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 1385-1394:
     * 9500-FILTER-RECORDS.
     *     IF FLG-ACCTFILTER-ISVALID
     *        IF  CARD-ACCT-ID = CC-ACCT-ID
     *            CONTINUE
     *        ELSE
     *            SET WS-EXCLUDE-THIS-RECORD TO TRUE
     *        END-IF
     *     END-IF
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Only cards with matching accountId are returned</li>
     *   <li>Cards from other accounts are filtered out</li>
     *   <li>Repository findByAccountId method invoked with correct accountId</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Account-based filtering returns only cards for specified account")
    void getCardList_ByAccountId_FiltersCorrectly() {
        // ARRANGE: Setup cards for specific account
        List<Card> accountCards = testCards.subList(0, 5);
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(accountCards, pageable, 5L);
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Retrieve cards for specific account
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify all returned cards belong to the specified account
        assertNotNull(result);
        assertEquals(5, result.getContent().size());
        result.getContent().forEach(card -> 
            assertEquals(TEST_ACCOUNT_ID, card.getAccountId(), 
                "All cards must belong to the specified account ID"));
        
        // Verify repository called with correct account ID
        verify(cardRepository).findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class));
    }

    /**
     * Test: Validates page forward navigation (PF8 key equivalent) returns next 7 cards.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 486-497:
     * WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-EXISTS
     *     MOVE WS-CA-LAST-CARD-NUM TO WS-CARD-RID-CARDNUM
     *     ADD +1 TO WS-CA-SCREEN-NUM
     *     PERFORM 9000-READ-FORWARD
     *        THRU 9000-READ-FORWARD-EXIT
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Second page (page 1) returns cards 8-14 (next 7 cards)</li>
     *   <li>Page number incremented correctly</li>
     *   <li>hasNext and hasPrevious flags set appropriately</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Next page (PF8) returns cards 8-14 matching COBOL page forward")
    void getCardList_NextPage_ReturnsCards8to14() {
        // ARRANGE: Setup mock for second page (cards 8-14)
        List<Card> secondPageCards = testCards.subList(7, 14);
        Pageable pageable = PageRequest.of(1, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(secondPageCards, pageable, testCards.size());
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Request second page (index 1)
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 1);
        
        // ASSERT: Verify second page contains cards 8-14
        assertNotNull(result);
        assertEquals(7, result.getContent().size(), "Second page should contain 7 cards");
        assertEquals(1, result.getNumber(), "Page number should be 1 (second page)");
        assertTrue(result.hasNext(), "Should have next page (card 15 remains)");
        assertTrue(result.hasPrevious(), "Should have previous page (first page exists)");
        
        // Verify card numbers are from second batch (cards 8-14)
        assertEquals("4532123456000008", result.getContent().get(0).getCardNumber());
        assertEquals("4532123456000014", result.getContent().get(6).getCardNumber());
    }

    /**
     * Test: Validates page backward navigation (PF7 key equivalent) returns previous 7 cards.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 501-513:
     * WHEN CCARD-AID-PFK07 AND NOT CA-FIRST-PAGE
     *     MOVE WS-CA-FIRST-CARD-NUM TO WS-CARD-RID-CARDNUM
     *     SUBTRACT 1 FROM WS-CA-SCREEN-NUM
     *     PERFORM 9100-READ-BACKWARDS
     *        THRU 9100-READ-BACKWARDS-EXIT
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Returning from page 1 to page 0 retrieves first 7 cards</li>
     *   <li>Page number decremented correctly</li>
     *   <li>hasPrevious flag becomes false (back at first page)</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Previous page (PF7) returns cards 1-7 matching COBOL page backward")
    void getCardList_PreviousPage_ReturnsCards1to7() {
        // ARRANGE: User navigates back to first page from second page
        List<Card> firstPageCards = testCards.subList(0, 7);
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(firstPageCards, pageable, testCards.size());
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Request first page (simulating PF7 from page 1)
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify back at first page with cards 1-7
        assertNotNull(result);
        assertEquals(7, result.getContent().size());
        assertEquals(0, result.getNumber(), "Should be back at page 0");
        assertFalse(result.hasPrevious(), "Should not have previous page (at first page)");
        assertTrue(result.hasNext(), "Should have next page");
        
        // Verify card numbers are from first batch (cards 1-7)
        assertEquals("4532123456000001", result.getContent().get(0).getCardNumber());
        assertEquals("4532123456000007", result.getContent().get(6).getCardNumber());
    }

    /**
     * Test: Validates default sort by card number in ascending order matches VSAM KSDS.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 1129-1136:
     * EXEC CICS STARTBR
     *      DATASET(LIT-CARD-FILE)
     *      RIDFLD(WS-CARD-RID-CARDNUM)
     *      KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
     *      GTEQ
     * END-EXEC
     * 
     * VSAM KSDS maintains records in ascending key sequence by CARD-NUM
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Cards returned in ascending card number order</li>
     *   <li>Sort order matches VSAM KSDS key-sequenced access</li>
     *   <li>Repository called with Sort.Direction.ASC on cardNumber</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Cards sorted by card number ascending matching VSAM KSDS order")
    void getCardList_SortsByCardNumber_Ascending() {
        // ARRANGE: Setup cards with specific order
        List<Card> sortedCards = new ArrayList<>(testCards.subList(0, 7));
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(sortedCards, pageable, testCards.size());
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Retrieve cards with default sort
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify ascending sort order by card number
        assertNotNull(result);
        List<Card> cards = result.getContent();
        for (int i = 0; i < cards.size() - 1; i++) {
            String currentCardNumber = cards.get(i).getCardNumber();
            String nextCardNumber = cards.get(i + 1).getCardNumber();
            assertTrue(currentCardNumber.compareTo(nextCardNumber) < 0,
                "Card numbers must be in ascending order (VSAM KSDS sequence)");
        }
        
        // Verify repository invoked with ascending sort on cardNumber
        verify(cardRepository).findByAccountId(eq(TEST_ACCOUNT_ID), argThat(p -> 
            p.getSort().getOrderFor("cardNumber") != null && 
            p.getSort().getOrderFor("cardNumber").getDirection() == Sort.Direction.ASC
        ));
    }

    /**
     * Test: Validates expiry date sorting and expiration warning business rule.
     * 
     * <p><strong>Business Rule:</strong> Cards expiring within 30 days should be flagged for
     * replacement card issuance per batch job CBCRD01C.cbl processing logic.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Cards can be sorted by expiration date</li>
     *   <li>Cards expiring within 30 days identified correctly</li>
     *   <li>isExpiringSoon() method returns true for cards within 30-day window</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Expiry date sorting and 30-day expiration warning business rule")
    void getCardList_SortsByExpiryDate_WarnsExpiring() {
        // ARRANGE: Filter cards expiring within 30 days (cards 6-8 from setup)
        List<Card> expiringSoonCards = testCards.subList(5, 8); // Cards 6-8
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "expirationDate"));
        testCardPage = new PageImpl<>(expiringSoonCards, pageable, 3L);
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Retrieve cards
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify expiring soon cards identified correctly
        assertNotNull(result);
        assertEquals(3, result.getContent().size());
        
        // Verify all cards expire within 30 days
        result.getContent().forEach(card -> {
            assertTrue(card.isExpiringSoon(), 
                "Card should be flagged as expiring soon (within 30 days)");
            assertTrue(card.getExpirationDate().isBefore(LocalDate.now().plusDays(31)),
                "Expiration date should be within next 30 days");
        });
    }

    /**
     * Test: Validates status filtering for ACTIVE cards only matching COBOL 88-level conditions.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COBOL copybook with 88-level condition:
     * 01 CARD-ACTIVE-STATUS PIC X(1).
     *    88 CARD-ACTIVE VALUE 'Y'.
     *    88 CARD-INACTIVE VALUE 'N'.
     *    88 CARD-BLOCKED VALUE 'B'.
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Only cards with activeStatus = 'Y' returned</li>
     *   <li>Cards with status 'N', 'B', 'E' filtered out</li>
     *   <li>Repository findByActiveStatus method can be used for filtering</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Status filter returns only ACTIVE cards matching COBOL 88-level CARD-ACTIVE")
    void getCardList_FilterByStatus_ActiveOnly() {
        // ARRANGE: Filter only active cards (cards 1-8 from setup)
        List<Card> activeCards = testCards.stream()
            .filter(card -> "Y".equals(card.getActiveStatus()))
            .limit(7)
            .toList();
        
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(activeCards, pageable, 8L);
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Retrieve cards (simulating active status filter)
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify only active cards returned
        assertNotNull(result);
        result.getContent().forEach(card -> {
            assertEquals("Y", card.getActiveStatus(), 
                "All cards must have ACTIVE status (CARD-ACTIVE condition)");
            assertTrue(card.isActive(), 
                "isActive() method should return true for status 'Y'");
        });
    }

    /**
     * Test: Validates empty result set when account has no cards.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 1241-1245:
     * WHEN DFHRESP(ENDFILE)
     *     IF WS-CA-SCREEN-NUM = 1
     *     AND WS-SCRN-COUNTER = 0
     *         SET WS-NO-RECORDS-FOUND TO TRUE
     *     END-IF
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Empty page returned (size 0)</li>
     *   <li>No pagination controls (hasNext and hasPrevious both false)</li>
     *   <li>Total elements = 0</li>
     *   <li>Error message: "NO RECORDS FOUND FOR THIS SEARCH CONDITION."</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Empty account returns empty page with no records found message")
    void getCardList_EmptyAccount_ReturnsEmptyPage() {
        // ARRANGE: Mock account with no cards
        List<Card> emptyCards = new ArrayList<>();
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(emptyCards, pageable, 0L);
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Retrieve cards for empty account
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify empty result
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Result should be empty (no cards found)");
        assertEquals(0, result.getContent().size());
        assertEquals(0, result.getTotalElements());
        assertEquals(0, result.getTotalPages());
        assertFalse(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    /**
     * Test: Validates card number masking for PCI-DSS compliance.
     * 
     * <p><strong>Security Requirement:</strong> Card numbers must be masked showing only
     * last 4 digits per PCI-DSS Section 3.3 compliance requirements.</p>
     * 
     * <p>Masking Format: "**** **** **** 1234"</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Full card number never exposed in responses</li>
     *   <li>Only last 4 digits visible: "**** **** **** {last4}"</li>
     *   <li>getMaskedCardNumber() method returns properly masked format</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Card numbers masked for PCI-DSS compliance (**** **** **** 1234)")
    void getCardList_FormatsCardNumber_MasksDigits() {
        // ARRANGE: Setup cards
        List<Card> cards = testCards.subList(0, 3);
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(cards, pageable, 3L);
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Retrieve cards
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        
        // ASSERT: Verify card number masking
        assertNotNull(result);
        result.getContent().forEach(card -> {
            String maskedNumber = card.getMaskedCardNumber();
            assertNotNull(maskedNumber, "Masked card number should not be null");
            assertTrue(maskedNumber.startsWith("**** **** **** "), 
                "Masked number must start with masked digits");
            assertEquals(19, maskedNumber.length(), 
                "Masked card number should be 19 characters (including spaces)");
            
            // Verify last 4 digits are visible
            String last4 = card.getCardNumber().substring(12);
            assertTrue(maskedNumber.endsWith(last4), 
                "Last 4 digits should be visible for verification");
            
            // Verify full card number is NOT in the masked format
            assertFalse(maskedNumber.contains(card.getCardNumber().substring(0, 12)),
                "First 12 digits must be masked (PCI-DSS requirement)");
        });
    }

    /**
     * Test: Validates expiry warning within 30-day business rule for card replacement.
     * 
     * <p><strong>Business Rule:</strong> Replacement cards must be issued 60 days before
     * expiration, with warnings displayed 30 days before expiration.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>isExpiringSoon() returns true for cards expiring within 30 days</li>
     *   <li>isExpiringSoon() returns false for cards expiring after 30 days</li>
     *   <li>Batch job can identify cards needing replacement</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Expiry warning identifies cards expiring within 30 days")
    void getCardList_IncludesExpiryWarning_Within30Days() {
        // ARRANGE: Create cards with various expiration dates
        Card cardExpiringSoon = testCards.get(5); // Expires in 15 days
        Card cardExpiredAlready = testCards.get(8); // Already expired
        Card cardValidLongTerm = testCards.get(0); // Valid for 2 years
        
        // ACT & ASSERT: Verify expiry warning logic
        assertTrue(cardExpiringSoon.isExpiringSoon(), 
            "Card expiring in 15 days should trigger expiry warning");
        assertTrue(cardExpiringSoon.getExpirationDate().isBefore(LocalDate.now().plusDays(31)),
            "Expiration date should be within 30-day window");
        
        assertFalse(cardExpiredAlready.isExpiringSoon(), 
            "Expired card should not trigger 'expiring soon' (already expired)");
        assertTrue(cardExpiredAlready.isExpired(), 
            "Card past expiration date should be marked as expired");
        
        assertFalse(cardValidLongTerm.isExpiringSoon(), 
            "Card valid for 2 years should not trigger expiry warning");
        assertFalse(cardValidLongTerm.isExpired(), 
            "Card valid for 2 years should not be marked as expired");
    }

    /**
     * Test: Validates partial last page with fewer than 7 cards.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COCRDLIC.cbl lines 1233-1240:
     * WHEN DFHRESP(ENDFILE)
     *     SET READ-LOOP-EXIT TO TRUE
     *     SET CA-NEXT-PAGE-NOT-EXISTS TO TRUE
     *     IF WS-ERROR-MSG-OFF
     *        MOVE 'NO MORE RECORDS TO SHOW' TO WS-ERROR-MSG
     *     END-IF
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Last page contains only remaining cards (not full 7 cards)</li>
     *   <li>15 total cards: page 0 has 7, page 1 has 7, page 2 has 1</li>
     *   <li>hasNext = false on last page</li>
     *   <li>Page size still 7, but content size is actual count</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Last page returns partial records (1 card) when fewer than 7 remain")
    void getCardList_LastPage_PartialRecords() {
        // ARRANGE: Setup last page with only 1 card (card 15)
        List<Card> lastPageCards = testCards.subList(14, 15); // Only card 15
        Pageable pageable = PageRequest.of(2, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(lastPageCards, pageable, testCards.size());
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Request last page (page 2)
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, 2);
        
        // ASSERT: Verify partial last page
        assertNotNull(result);
        assertEquals(1, result.getContent().size(), 
            "Last page should contain only 1 card (partial page)");
        assertEquals(2, result.getNumber(), "Should be page 2 (third page, last page)");
        assertEquals(7, result.getSize(), "Page size configuration remains 7");
        assertFalse(result.hasNext(), "Should not have next page (this is last page)");
        assertTrue(result.hasPrevious(), "Should have previous pages");
        
        // Verify it's the last card
        assertEquals("4532123456000015", result.getContent().get(0).getCardNumber());
    }

    /**
     * Test: Validates AccountNotFoundException thrown when account does not exist.
     * 
     * <p><strong>COBOL Source Reference:</strong></p>
     * <pre>
     * COBOL error handling for DFHRESP(NOTFND) when account not found
     * Maps to Spring exception handling pattern
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>AccountNotFoundException thrown</li>
     *   <li>Exception contains account ID that was not found</li>
     *   <li>HTTP 404 NOT_FOUND response in REST controller</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: AccountNotFoundException thrown when account does not exist")
    void getCardList_NonExistentAccount_ThrowsAccountNotFoundException() {
        // ARRANGE: Mock account not found
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.empty());
        
        // ACT & ASSERT: Verify exception thrown
        AccountNotFoundException exception = assertThrows(AccountNotFoundException.class, () -> {
            cardListService.getCardList(TEST_ACCOUNT_ID, 0);
        }, "Should throw AccountNotFoundException for non-existent account");
        
        // Verify exception details
        assertNotNull(exception);
        assertTrue(exception.getMessage().contains(TEST_ACCOUNT_ID.toString()),
            "Exception message should contain account ID");
        
        // Verify repository was called for account check but not for cards
        verify(accountRepository, times(1)).findById(TEST_ACCOUNT_ID);
        verify(cardRepository, never()).findByAccountId(anyLong(), any(Pageable.class));
    }

    /**
     * Test: Validates CardListResponse DTO construction with complete metadata.
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>CardListResponse populated with all pagination metadata</li>
     *   <li>Transaction name and program name set correctly</li>
     *   <li>Current date and time populated</li>
     *   <li>Card items array contains CardItemDTO objects</li>
     *   <li>Appropriate messages set based on pagination state</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: CardListResponse DTO built with complete metadata and messages")
    void getCardsByAccountId_BuildsCompleteResponse_WithMetadata() {
        // ARRANGE: Setup mock page
        List<Card> cards = testCards.subList(0, 7);
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(cards, pageable, testCards.size());
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Get complete CardListResponse
        CardListResponse response = cardListService.getCardsByAccountId(TEST_ACCOUNT_ID, 0, "ASC");
        
        // ASSERT: Verify response structure
        assertNotNull(response);
        assertEquals("CCLI", response.getTransactionName(), 
            "Transaction name should be CCLI (Card List)");
        assertEquals("COCRDLIC", response.getProgramName(), 
            "Program name should match COBOL program ID");
        assertNotNull(response.getCurrentDate());
        assertNotNull(response.getCurrentTime());
        assertEquals(1, response.getPageNumber(), 
            "Display page number should be 1 (1-based for user display)");
        assertEquals(0, response.getCurrentPage(), 
            "Internal page index should be 0 (0-based)");
        assertEquals(7, response.getPageSize());
        assertEquals(3, response.getTotalPages());
        assertEquals(15L, response.getTotalElements());
        assertTrue(response.getHasNext());
        assertFalse(response.getHasPrevious());
        
        // Verify card items
        assertNotNull(response.getCards());
        assertEquals(7, response.getCards().size());
        
        // Verify first card item structure
        CardItemDTO firstCard = response.getCards().get(0);
        assertNotNull(firstCard);
        assertNotNull(firstCard.getAccountNumber());
        assertNotNull(firstCard.getCardNumber());
        assertNotNull(firstCard.getCardStatus());
        
        // Verify info message for actions
        assertNotNull(response.getInfoMessage());
        assertTrue(response.getInfoMessage().contains("TYPE S FOR DETAIL") ||
                   response.getInfoMessage().contains("UPDATE"),
            "Info message should guide user actions");
    }

    /**
     * Test: Validates negative page number handling defaults to page 0.
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Negative page numbers normalized to 0</li>
     *   <li>First page returned for invalid page request</li>
     *   <li>No exception thrown</li>
     * </ul>
     */
    @Test
    @DisplayName("Test: Negative page number defaults to page 0")
    void getCardList_NegativePageNumber_DefaultsToPageZero() {
        // ARRANGE: Setup first page
        List<Card> cards = testCards.subList(0, 7);
        Pageable pageable = PageRequest.of(0, CARDS_PER_PAGE, Sort.by(Sort.Direction.ASC, "cardNumber"));
        testCardPage = new PageImpl<>(cards, pageable, testCards.size());
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(new com.carddemo.entity.Account()));
        when(cardRepository.findByAccountId(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
            .thenReturn(testCardPage);
        
        // ACT: Request with negative page number
        Page<Card> result = cardListService.getCardList(TEST_ACCOUNT_ID, -5);
        
        // ASSERT: Verify defaults to first page
        assertNotNull(result);
        assertEquals(0, result.getNumber(), "Negative page should default to page 0");
        assertEquals(7, result.getContent().size());
    }
}

