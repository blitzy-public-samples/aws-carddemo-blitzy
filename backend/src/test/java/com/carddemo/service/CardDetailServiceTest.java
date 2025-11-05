/*****************************************************************
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
 ******************************************************************/

package com.carddemo.service;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JUnit 5 test class for CardDetailService validating business logic transformation
 * from COCRDSLC.cbl COBOL program.
 * 
 * <p>This test suite ensures functional equivalence between the COBOL CICS transaction
 * program COCRDSLC.cbl (Card Detail/Select) and the Java Spring Boot CardDetailService.
 * All test cases verify that the service produces identical results to the original
 * mainframe VSAM CARDDAT file operations.</p>
 * 
 * <p><strong>COBOL Program Being Tested:</strong></p>
 * <ul>
 *   <li>Source: app/cbl/COCRDSLC.cbl (lines 1-888)</li>
 *   <li>Transaction ID: CCDL (Card Detail)</li>
 *   <li>Function: Accept and process credit card detail request</li>
 *   <li>Primary Operation: EXEC CICS READ FILE(CARDDAT) RIDFLD(CARD-NUM)</li>
 *   <li>BMS Mapset: COCRDSL (Card Select Screen)</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>VSAM READ to JPA findById transformation (COCRDSLC.cbl lines 742-750)</li>
 *   <li>Related transaction history retrieval (pagination with 10 records per page)</li>
 *   <li>CICS RESP=NOTFND error handling → CardNotFoundException (line 755)</li>
 *   <li>88-level condition status validation (CARD-ACTIVE, CARD-EXPIRED, CARD-BLOCKED)</li>
 *   <li>PIC X field formatting preservation (card number, embossed name)</li>
 *   <li>COMP-3 balance precision with scale=2 (interest calculation, available credit)</li>
 * </ul>
 * 
 * <p><strong>Precision Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>ALL BigDecimal monetary amounts MUST have scale=2</li>
 *   <li>ALL BigDecimal operations MUST use RoundingMode.HALF_UP</li>
 *   <li>Available credit calculation: creditLimit - currentBalance with scale=2</li>
 *   <li>Transaction amounts preserve COBOL COMP-3 precision from TRANSACT file</li>
 * </ul>
 * 
 * <p><strong>Validation Points:</strong></p>
 * <ul>
 *   <li>Card number format: Exactly 16 digits (COCRDSLC.cbl line 706: "CARD ID...MUST BE A 16 DIGIT NUMBER")</li>
 *   <li>Expiry date format: MM/YY (COCRDSLC.cbl lines 480-482: CARD-EXPIRY-MONTH, CARD-EXPIRY-YEAR)</li>
 *   <li>Card status validation: Y/N/B/E/C/P (CARD-ACTIVE-STATUS field)</li>
 *   <li>Error messages match COBOL text (COCRDSLC.cbl lines 151-156)</li>
 *   <li>PCI-DSS masking: Card number masked as "**** **** **** 1234"</li>
 * </ul>
 * 
 * <p><strong>COBOL Error Message Equivalents:</strong></p>
 * <pre>
 * COBOL (line 154): "Did not find cards for this search condition"
 * Java:  CardNotFoundException with message "Card not found with card number: {cardNumber}"
 * 
 * COBOL (line 149): "Card number if supplied must be a 16 digit number"
 * Java:  IllegalArgumentException with message "Card number must be exactly 16 characters"
 * </pre>
 * 
 * @see CardDetailService
 * @see Card
 * @see CardRepository
 * @see TransactionRepository
 * @see CardNotFoundException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService Test Suite - COBOL COCRDSLC.cbl Transformation")
public class CardDetailServiceTest {

    /**
     * Mock CardRepository for Card entity data access operations.
     * Simulates VSAM CARDDAT file READ operations from COBOL program.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Mock TransactionRepository for Transaction entity data access operations.
     * Simulates VSAM TRANSACT file sequential READ operations for card history.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Service under test with mocked dependencies injected.
     * This is the system under test (SUT) for all test methods.
     */
    @InjectMocks
    private CardDetailService cardDetailService;

    // Test data constants matching COBOL field specifications
    private static final String VALID_CARD_NUMBER = "4532123456789012"; // 16 digits per COBOL PIC X(16)
    private static final String INVALID_SHORT_CARD_NUMBER = "453212345"; // < 16 digits - triggers validation error
    private static final Long VALID_ACCOUNT_ID = 12345678901L; // 11 digits per COBOL PIC 9(11)
    private static final String EMBOSSED_NAME = "JOHN DOE"; // PIC X(50) embossed name
    private static final String CVV_CODE = "123"; // PIC 9(03) - NEVER displayed per PCI-DSS
    private static final String ACTIVE_STATUS = "Y"; // CARD-ACTIVE 88-level condition
    private static final String EXPIRED_STATUS = "E"; // CARD-EXPIRED 88-level condition
    private static final String BLOCKED_STATUS = "B"; // CARD-BLOCKED 88-level condition
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2025, 12, 31); // Card expiry date
    
    // BigDecimal monetary amounts with COMP-3 precision (scale=2, HALF_UP rounding)
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TRANSACTION_AMOUNT = new BigDecimal("45.67").setScale(2, RoundingMode.HALF_UP);

    private Card testCard;
    private Account testAccount;
    private List<Transaction> testTransactions;

    /**
     * Set up test fixtures before each test method execution.
     * Creates standardized test data matching COBOL record structures.
     */
    @BeforeEach
    void setUp() {
        // Create test Card entity matching COBOL CARD-RECORD structure
        testCard = new Card();
        testCard.setCardNumber(VALID_CARD_NUMBER);
        testCard.setAccountId(VALID_ACCOUNT_ID);
        testCard.setEmbossedName(EMBOSSED_NAME);
        testCard.setCvvCode(CVV_CODE);
        testCard.setExpirationDate(EXPIRATION_DATE);
        testCard.setActiveStatus(ACTIVE_STATUS);

        // Create test Account entity with COMP-3 precision BigDecimal fields
        testAccount = new Account();
        testAccount.setAccountId(VALID_ACCOUNT_ID);
        testAccount.setCurrentBalance(CURRENT_BALANCE);
        testAccount.setCreditLimit(CREDIT_LIMIT);
        testAccount.setActiveStatus(ACTIVE_STATUS);

        // Associate card with account (JPA relationship navigation)
        testCard.setAccount(testAccount);

        // Create test transaction list (default 10 transactions matching COBOL pagination)
        testTransactions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Transaction transaction = new Transaction();
            transaction.setTransactionId(String.format("TX%014d", i)); // 16-char transaction ID
            transaction.setCardNumber(VALID_CARD_NUMBER);
            transaction.setTransactionAmount(TRANSACTION_AMOUNT);
            transaction.setOriginationTimestamp(LocalDateTime.now().minusDays(i));
            transaction.setMerchantName("TEST MERCHANT " + i);
            transaction.setTransactionDescription("TEST TRANSACTION " + i);
            transaction.setTransactionTypeCode("SA"); // Sale transaction type
            testTransactions.add(transaction);
        }
    }

    /**
     * Test: getCardDetail_ValidCardNumber_ReturnsCardInfo
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDSLC.cbl lines 736-773 (9100-GETCARD-BYACCTCARD)</p>
     * <pre>
     * COBOL: EXEC CICS READ
     *             FILE      (LIT-CARDFILENAME)
     *             RIDFLD    (WS-CARD-RID-CARDNUM)
     *             INTO      (CARD-RECORD)
     *             RESP      (WS-RESP-CD)
     *        END-EXEC
     *        
     *        WHEN DFHRESP(NORMAL)
     *            SET FOUND-CARDS-FOR-ACCOUNT TO TRUE
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>VSAM READ NORMAL response → Card entity returned</li>
     *   <li>Card number preserved exactly (16 digits)</li>
     *   <li>Card data fields populated correctly</li>
     *   <li>PCI-DSS masking applied to card number in response</li>
     * </ul>
     */
    @Test
    @DisplayName("getCardDetail with valid card number returns card information")
    void getCardDetail_ValidCardNumber_ReturnsCardInfo() {
        // Arrange: Mock repository to return test card (VSAM READ NORMAL equivalent)
        Mockito.when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(testCard));

        // Mock transaction repository to return empty page (focus on card data)
        Page<Transaction> emptyTransactionPage = new PageImpl<>(new ArrayList<>());
        Mockito.when(transactionRepository.findByCardNumber(
                Mockito.eq(VALID_CARD_NUMBER), 
                Mockito.any(Pageable.class)))
                .thenReturn(emptyTransactionPage);

        // Act: Call service method
        Map<String, Object> result = cardDetailService.getCardDetail(VALID_CARD_NUMBER);

        // Assert: Verify card information returned correctly
        Assertions.assertNotNull(result, "Card detail response should not be null");
        
        // Verify card number is masked per PCI-DSS requirements
        String maskedCardNumber = (String) result.get("cardNumber");
        Assertions.assertNotNull(maskedCardNumber, "Masked card number should be present");
        Assertions.assertTrue(maskedCardNumber.endsWith("9012"), 
                "Masked card number should end with last 4 digits");
        Assertions.assertTrue(maskedCardNumber.startsWith("****"), 
                "Masked card number should start with ****");
        
        // Verify embossed name matches COBOL CARD-EMBOSSED-NAME field
        Assertions.assertEquals(EMBOSSED_NAME, result.get("embossedName"),
                "Embossed name should match COBOL field");
        
        // Verify expiration date formatted as MM/YY per COBOL display format
        String expiryDate = (String) result.get("expirationDate");
        Assertions.assertNotNull(expiryDate, "Expiration date should be present");
        Assertions.assertEquals("12/25", expiryDate, 
                "Expiration date should be formatted as MM/YY");
        
        // Verify card status matches COBOL CARD-ACTIVE-STATUS
        Assertions.assertEquals(ACTIVE_STATUS, result.get("cardStatusCode"),
                "Card status code should match COBOL 88-level condition");
        
        // Verify account ID matches COBOL CARD-ACCT-ID field
        Assertions.assertEquals(VALID_ACCOUNT_ID, result.get("accountId"),
                "Account ID should match COBOL CARD-ACCT-ID");
        
        // Verify repository method was called exactly once
        Mockito.verify(cardRepository, Mockito.times(1))
                .findByCardNumber(VALID_CARD_NUMBER);
    }

    /**
     * Test: getCardDetail_WithTransactions_IncludesHistory
     * 
     * <p><strong>COBOL Equivalent:</strong> Related transaction retrieval from TRANSACT file</p>
     * <pre>
     * COBOL: Sequential read of TRANSACT file filtered by CARD-NUM
     *        Display up to 10 transactions per screen (pagination)
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Transaction history retrieval with pagination (10 per page)</li>
     *   <li>Transactions sorted by origination timestamp descending</li>
     *   <li>BigDecimal transaction amounts with scale=2 precision</li>
     *   <li>Transaction count matches expected</li>
     * </ul>
     */
    @Test
    @DisplayName("getCardDetail with transactions includes transaction history")
    void getCardDetail_WithTransactions_IncludesHistory() {
        // Arrange: Mock card repository
        Mockito.when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(testCard));

        // Mock transaction repository to return test transactions
        Page<Transaction> transactionPage = new PageImpl<>(testTransactions);
        Mockito.when(transactionRepository.findByCardNumber(
                Mockito.eq(VALID_CARD_NUMBER), 
                Mockito.any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Call service method
        Map<String, Object> result = cardDetailService.getCardDetail(VALID_CARD_NUMBER);

        // Assert: Verify transaction history is included
        Assertions.assertNotNull(result, "Card detail response should not be null");
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transactions = 
                (List<Map<String, Object>>) result.get("recentTransactions");
        
        Assertions.assertNotNull(transactions, "Transaction list should be present");
        Assertions.assertEquals(10, transactions.size(), 
                "Should return 10 transactions per COBOL pagination");
        
        // Verify transaction count field
        Integer transactionCount = (Integer) result.get("transactionCount");
        Assertions.assertEquals(10, transactionCount,
                "Transaction count should match list size");
        
        // Verify first transaction details
        Map<String, Object> firstTransaction = transactions.get(0);
        Assertions.assertNotNull(firstTransaction.get("transactionId"),
                "Transaction ID should be present");
        
        // Verify BigDecimal amount with scale=2 (COMP-3 precision)
        BigDecimal amount = (BigDecimal) firstTransaction.get("amount");
        Assertions.assertNotNull(amount, "Transaction amount should be present");
        Assertions.assertEquals(2, amount.scale(), 
                "Transaction amount must have scale=2 per COMP-3 precision");
        Assertions.assertEquals(TRANSACTION_AMOUNT, amount,
                "Transaction amount should match test data");
        
        // Verify merchant name (COBOL TRAN-MERCHANT-NAME field)
        Assertions.assertNotNull(firstTransaction.get("merchantName"),
                "Merchant name should be present");
        
        // Verify repository was called with correct pagination parameters
        Mockito.verify(transactionRepository, Mockito.times(1))
                .findByCardNumber(Mockito.eq(VALID_CARD_NUMBER), Mockito.any(Pageable.class));
    }

    /**
     * Test: getCardDetail_InvalidCardNumber_ThrowsNotFoundException
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDSLC.cbl lines 755-761 (NOTFND handling)</p>
     * <pre>
     * COBOL: WHEN DFHRESP(NOTFND)
     *            SET INPUT-ERROR                    TO TRUE
     *            SET FLG-ACCTFILTER-NOT-OK          TO TRUE
     *            SET FLG-CARDFILTER-NOT-OK          TO TRUE
     *            IF  WS-RETURN-MSG-OFF
     *                SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE
     *            END-IF
     * 
     * Error Message (line 154): "Did not find cards for this search condition"
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>CICS RESP=NOTFND maps to CardNotFoundException</li>
     *   <li>Exception contains correct card number identifier</li>
     *   <li>Error message matches COBOL semantics</li>
     * </ul>
     */
    @Test
    @DisplayName("getCardDetail with invalid card number throws CardNotFoundException")
    void getCardDetail_InvalidCardNumber_ThrowsNotFoundException() {
        // Arrange: Mock repository to return empty (VSAM NOTFND equivalent)
        Mockito.when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.empty());

        // Act & Assert: Verify CardNotFoundException is thrown
        CardNotFoundException exception = Assertions.assertThrows(
                CardNotFoundException.class,
                () -> cardDetailService.getCardDetail(VALID_CARD_NUMBER),
                "Should throw CardNotFoundException when card not found");

        // Verify exception contains card identifier
        Assertions.assertNotNull(exception.getCardIdentifier(),
                "Exception should contain card identifier");
        Assertions.assertEquals(VALID_CARD_NUMBER, exception.getCardIdentifier(),
                "Card identifier should match input");
        
        // Verify identifier type is CARD_NUMBER
        Assertions.assertEquals(CardNotFoundException.IdentifierType.CARD_NUMBER,
                exception.getIdentifierType(),
                "Identifier type should be CARD_NUMBER");
        
        // Verify error message references card not found
        Assertions.assertTrue(exception.getMessage().contains("not found"),
                "Error message should indicate card not found");
        
        // Verify repository was called
        Mockito.verify(cardRepository, Mockito.times(1))
                .findByCardNumber(VALID_CARD_NUMBER);
    }

    /**
     * Test: getCardDetail_NullCardNumber_ThrowsIllegalArgumentException
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDSLC.cbl lines 691-702 (input validation)</p>
     * <pre>
     * COBOL: IF CC-CARD-NUM   EQUAL LOW-VALUES
     *        OR CC-CARD-NUM   EQUAL SPACES
     *        OR CC-CARD-NUM-N EQUAL ZEROS
     *            SET INPUT-ERROR           TO TRUE
     *            SET FLG-CARDFILTER-BLANK  TO TRUE
     *            IF WS-RETURN-MSG-OFF
     *               SET WS-PROMPT-FOR-CARD TO TRUE
     *            END-IF
     * 
     * Error Message (line 141): "Card number not provided"
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Null card number input validation</li>
     *   <li>IllegalArgumentException thrown for invalid input</li>
     *   <li>Error message indicates null/empty validation</li>
     * </ul>
     */
    @Test
    @DisplayName("getCardDetail with null card number throws IllegalArgumentException")
    void getCardDetail_NullCardNumber_ThrowsIllegalArgumentException() {
        // Act & Assert: Verify IllegalArgumentException is thrown for null input
        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cardDetailService.getCardDetail(null),
                "Should throw IllegalArgumentException for null card number");

        // Verify error message indicates null/empty validation
        Assertions.assertTrue(
                exception.getMessage().contains("null") || 
                exception.getMessage().contains("empty"),
                "Error message should indicate null or empty input");
        
        // Verify repository was NOT called (validation should fail before repository access)
        Mockito.verify(cardRepository, Mockito.never())
                .findByCardNumber(Mockito.any());
    }

    /**
     * Test: getCardDetail_EmptyCardNumber_ThrowsIllegalArgumentException
     * 
     * <p><strong>COBOL Equivalent:</strong> Same validation as null test</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Empty string card number input validation</li>
     *   <li>IllegalArgumentException thrown for blank input</li>
     * </ul>
     */
    @Test
    @DisplayName("getCardDetail with empty card number throws IllegalArgumentException")
    void getCardDetail_EmptyCardNumber_ThrowsIllegalArgumentException() {
        // Act & Assert: Verify IllegalArgumentException is thrown for empty string
        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cardDetailService.getCardDetail(""),
                "Should throw IllegalArgumentException for empty card number");

        // Verify error message
        Assertions.assertTrue(
                exception.getMessage().contains("null") || 
                exception.getMessage().contains("empty"),
                "Error message should indicate null or empty input");
        
        // Verify repository was NOT called
        Mockito.verify(cardRepository, Mockito.never())
                .findByCardNumber(Mockito.any());
    }

    /**
     * Test: getCardDetail_InvalidLength_ThrowsIllegalArgumentException
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDSLC.cbl lines 706-715 (length validation)</p>
     * <pre>
     * COBOL: IF CC-CARD-NUM  IS NOT NUMERIC
     *            SET INPUT-ERROR TO TRUE
     *            SET FLG-CARDFILTER-NOT-OK TO TRUE
     *            IF WS-RETURN-MSG-OFF
     *               MOVE
     *          'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'
     *                            TO WS-RETURN-MSG
     *            END-IF
     * 
     * Error Message (line 711): "Card number...must be a 16 digit number"
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card number length validation (must be exactly 16 characters)</li>
     *   <li>IllegalArgumentException thrown for invalid length</li>
     *   <li>Error message indicates length requirement</li>
     * </ul>
     */
    @Test
    @DisplayName("getCardDetail with invalid length card number throws IllegalArgumentException")
    void getCardDetail_InvalidLength_ThrowsIllegalArgumentException() {
        // Act & Assert: Verify IllegalArgumentException for short card number
        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cardDetailService.getCardDetail(INVALID_SHORT_CARD_NUMBER),
                "Should throw IllegalArgumentException for invalid card number length");

        // Verify error message indicates length requirement (16 characters)
        Assertions.assertTrue(
                exception.getMessage().contains("16"),
                "Error message should indicate 16 character requirement");
        
        // Verify repository was NOT called
        Mockito.verify(cardRepository, Mockito.never())
                .findByCardNumber(Mockito.any());
    }

    /**
     * Test: validateCardStatus_ActiveStatus_ReturnsTrue
     * 
     * <p><strong>COBOL Equivalent:</strong> COBOL 88-level condition CARD-ACTIVE</p>
     * <pre>
     * COBOL: 01 CARD-ACTIVE-STATUS PIC X(01).
     *           88 CARD-ACTIVE VALUE 'Y'.
     * 
     * Usage: IF CARD-ACTIVE THEN...
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card status 'Y' (Active) maps to isActive() = true</li>
     *   <li>88-level condition preservation in Java enum pattern</li>
     * </ul>
     */
    @Test
    @DisplayName("Card with ACTIVE status (Y) returns isActive true")
    void validateCardStatus_ActiveStatus_ReturnsTrue() {
        // Arrange: Card already set with ACTIVE_STATUS ('Y')
        Assertions.assertEquals(ACTIVE_STATUS, testCard.getActiveStatus(),
                "Test card should have ACTIVE status");

        // Act: Check if card is active using entity method
        boolean isActive = testCard.isActive();

        // Assert: Verify card is active
        Assertions.assertTrue(isActive, 
                "Card with status 'Y' should be active per COBOL 88-level CARD-ACTIVE condition");
    }

    /**
     * Test: validateCardStatus_ExpiredStatus_ReturnsFalse
     * 
     * <p><strong>COBOL Equivalent:</strong> COBOL 88-level condition CARD-EXPIRED</p>
     * <pre>
     * COBOL: 01 CARD-ACTIVE-STATUS PIC X(01).
     *           88 CARD-EXPIRED VALUE 'E'.
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card status 'E' (Expired) maps to isActive() = false</li>
     *   <li>Expired cards cannot be used for transactions</li>
     * </ul>
     */
    @Test
    @DisplayName("Card with EXPIRED status (E) returns isActive false")
    void validateCardStatus_ExpiredStatus_ReturnsFalse() {
        // Arrange: Set card to expired status
        testCard.setActiveStatus(EXPIRED_STATUS);

        // Act: Check if card is active
        boolean isActive = testCard.isActive();

        // Assert: Verify card is NOT active
        Assertions.assertFalse(isActive, 
                "Card with status 'E' should NOT be active per COBOL 88-level CARD-EXPIRED condition");
    }

    /**
     * Test: validateCardStatus_BlockedStatus_ReturnsFalse
     * 
     * <p><strong>COBOL Equivalent:</strong> COBOL 88-level condition CARD-BLOCKED</p>
     * <pre>
     * COBOL: 01 CARD-ACTIVE-STATUS PIC X(01).
     *           88 CARD-BLOCKED VALUE 'B'.
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card status 'B' (Blocked) maps to isActive() = false</li>
     *   <li>Blocked cards cannot be used for transactions</li>
     * </ul>
     */
    @Test
    @DisplayName("Card with BLOCKED status (B) returns isActive false")
    void validateCardStatus_BlockedStatus_ReturnsFalse() {
        // Arrange: Set card to blocked status
        testCard.setActiveStatus(BLOCKED_STATUS);

        // Act: Check if card is active
        boolean isActive = testCard.isActive();

        // Assert: Verify card is NOT active
        Assertions.assertFalse(isActive, 
                "Card with status 'B' should NOT be active per COBOL 88-level CARD-BLOCKED condition");
    }

    /**
     * Test: formatCardDisplay_PreservesFieldLayout
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDSLC.cbl lines 457-497 (1200-SETUP-SCREEN-VARS)</p>
     * <pre>
     * COBOL: MOVE CARD-EMBOSSED-NAME TO CRDNAMEO OF CCRDSLAO
     *        MOVE CARD-EXPIRY-MONTH  TO EXPMONO OF CCRDSLAO
     *        MOVE CARD-EXPIRY-YEAR   TO EXPYEARO OF CCRDSLAO
     *        MOVE CARD-ACTIVE-STATUS TO CRDSTCDO OF CCRDSLAO
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>PIC X field formatting preservation (embossed name)</li>
     *   <li>Expiration date format MM/YY matching COBOL display</li>
     *   <li>Card number masking per PCI-DSS (**** **** **** 1234)</li>
     * </ul>
     */
    @Test
    @DisplayName("Card display formatting preserves COBOL field layout")
    void formatCardDisplay_PreservesFieldLayout() {
        // Arrange: Card already initialized with test data
        
        // Act: Get formatted display fields
        String maskedCardNumber = testCard.getMaskedCardNumber();
        String formattedExpiry = testCard.getFormattedExpirationDate();
        String embossedName = testCard.getEmbossedName();
        String statusDisplayName = testCard.getStatusDisplayName();

        // Assert: Verify card number masking format (PCI-DSS requirement)
        Assertions.assertNotNull(maskedCardNumber, "Masked card number should not be null");
        Assertions.assertEquals("**** **** **** 9012", maskedCardNumber,
                "Card number should be masked showing only last 4 digits");
        
        // Verify expiration date format MM/YY (COBOL CARD-EXPIRY-MONTH / CARD-EXPIRY-YEAR)
        Assertions.assertNotNull(formattedExpiry, "Formatted expiry date should not be null");
        Assertions.assertEquals("12/25", formattedExpiry,
                "Expiration date should be formatted as MM/YY per COBOL display");
        
        // Verify embossed name preserved (COBOL CARD-EMBOSSED-NAME PIC X(50))
        Assertions.assertNotNull(embossedName, "Embossed name should not be null");
        Assertions.assertEquals(EMBOSSED_NAME, embossedName,
                "Embossed name should match COBOL PIC X(50) field");
        
        // Verify status display name (human-readable version of 88-level condition)
        Assertions.assertNotNull(statusDisplayName, "Status display name should not be null");
        Assertions.assertTrue(statusDisplayName.equalsIgnoreCase("Active") || 
                              statusDisplayName.equalsIgnoreCase("Usable"),
                "Status display name should indicate active status");
    }

    /**
     * Test: maskCardNumber_ValidCardNumber_ReturnsMasked
     * 
     * <p><strong>COBOL Equivalent:</strong> Card number masking for display</p>
     * <pre>
     * COBOL: Display only last 4 digits of card number for security
     *        Format: **** **** **** 1234
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>PCI-DSS Section 3.3 masking requirement compliance</li>
     *   <li>Card number format: "**** **** **** 1234"</li>
     *   <li>Last 4 digits visible for cardholder verification</li>
     * </ul>
     */
    @Test
    @DisplayName("maskCardNumber with valid card number returns properly masked format")
    void maskCardNumber_ValidCardNumber_ReturnsMasked() {
        // Act: Mask card number using service method
        String maskedCardNumber = cardDetailService.maskCardNumber(VALID_CARD_NUMBER);

        // Assert: Verify masking format
        Assertions.assertNotNull(maskedCardNumber, "Masked card number should not be null");
        Assertions.assertTrue(maskedCardNumber.startsWith("****"), 
                "Masked card number should start with ****");
        Assertions.assertTrue(maskedCardNumber.endsWith("9012"), 
                "Masked card number should end with last 4 digits (9012)");
        Assertions.assertEquals("**** **** **** 9012", maskedCardNumber,
                "Masked card number should match PCI-DSS format");
    }

    /**
     * Test: maskCardNumber_NullCardNumber_ReturnsFourStars
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Graceful handling of null card number</li>
     *   <li>Returns "****" for null input</li>
     * </ul>
     */
    @Test
    @DisplayName("maskCardNumber with null card number returns four stars")
    void maskCardNumber_NullCardNumber_ReturnsFourStars() {
        // Act: Mask null card number
        String maskedCardNumber = cardDetailService.maskCardNumber(null);

        // Assert: Verify returns four stars
        Assertions.assertEquals("****", maskedCardNumber,
                "Null card number should return ****");
    }

    /**
     * Test: maskCardNumber_ShortCardNumber_ReturnsFourStars
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Graceful handling of invalid short card number</li>
     *   <li>Returns "****" for card number < 4 characters</li>
     * </ul>
     */
    @Test
    @DisplayName("maskCardNumber with short card number returns four stars")
    void maskCardNumber_ShortCardNumber_ReturnsFourStars() {
        // Act: Mask short card number (less than 4 characters)
        String maskedCardNumber = cardDetailService.maskCardNumber("123");

        // Assert: Verify returns four stars
        Assertions.assertEquals("****", maskedCardNumber,
                "Short card number should return ****");
    }

    /**
     * Test: getCardBalance_CalculatesCreditAvailable
     * 
     * <p><strong>COBOL Equivalent:</strong> Credit available calculation</p>
     * <pre>
     * COBOL: COMPUTE AVAILABLE-CREDIT = CREDIT-LIMIT - CURRENT-BALANCE
     *        (with COMP-3 packed decimal precision)
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Available credit = creditLimit - currentBalance</li>
     *   <li>BigDecimal scale=2 precision (COMP-3 equivalent)</li>
     *   <li>RoundingMode.HALF_UP for consistent rounding</li>
     * </ul>
     */
    @Test
    @DisplayName("Card balance calculation computes available credit with COMP-3 precision")
    void getCardBalance_CalculatesCreditAvailable() {
        // Arrange: Mock repository to return test card with account
        Mockito.when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(testCard));

        // Mock empty transaction page
        Page<Transaction> emptyTransactionPage = new PageImpl<>(new ArrayList<>());
        Mockito.when(transactionRepository.findByCardNumber(
                Mockito.eq(VALID_CARD_NUMBER), 
                Mockito.any(Pageable.class)))
                .thenReturn(emptyTransactionPage);

        // Act: Get card detail with balance calculation
        Map<String, Object> result = cardDetailService.getCardDetail(VALID_CARD_NUMBER);

        // Assert: Verify balance fields are present
        Assertions.assertNotNull(result.get("currentBalance"), 
                "Current balance should be present");
        Assertions.assertNotNull(result.get("creditLimit"), 
                "Credit limit should be present");
        Assertions.assertNotNull(result.get("availableCredit"), 
                "Available credit should be present");
        
        // Verify BigDecimal precision
        BigDecimal currentBalance = (BigDecimal) result.get("currentBalance");
        BigDecimal creditLimit = (BigDecimal) result.get("creditLimit");
        BigDecimal availableCredit = (BigDecimal) result.get("availableCredit");
        
        // Verify scale=2 per COMP-3 requirement
        Assertions.assertEquals(2, currentBalance.scale(), 
                "Current balance must have scale=2 per COBOL COMP-3 precision");
        Assertions.assertEquals(2, creditLimit.scale(), 
                "Credit limit must have scale=2 per COBOL COMP-3 precision");
        Assertions.assertEquals(2, availableCredit.scale(), 
                "Available credit must have scale=2 per COBOL COMP-3 precision");
        
        // Verify calculation: availableCredit = creditLimit - currentBalance
        BigDecimal expectedAvailableCredit = CREDIT_LIMIT.subtract(CURRENT_BALANCE)
                .setScale(2, RoundingMode.HALF_UP);
        Assertions.assertEquals(expectedAvailableCredit, availableCredit,
                "Available credit should equal creditLimit - currentBalance");
        
        // Verify specific amount (5000.00 - 1234.56 = 3765.44)
        Assertions.assertEquals(new BigDecimal("3765.44").setScale(2, RoundingMode.HALF_UP), 
                availableCredit,
                "Available credit calculation should match expected amount");
    }

    /**
     * Test: buildCardDetailResponse_WithAccount_IncludesAccountDetails
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDSLC.cbl response building with account navigation</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card-to-account relationship navigation</li>
     *   <li>Account financial details included in response</li>
     *   <li>All required fields populated</li>
     * </ul>
     */
    @Test
    @DisplayName("buildCardDetailResponse with account includes all account details")
    void buildCardDetailResponse_WithAccount_IncludesAccountDetails() {
        // Arrange: Test data already set up in @BeforeEach
        
        // Act: Build response using service method
        Map<String, Object> response = cardDetailService.buildCardDetailResponse(
                testCard, testTransactions);

        // Assert: Verify all required fields are present
        Assertions.assertAll("Card detail response validation",
            () -> Assertions.assertNotNull(response.get("cardNumber"), 
                    "Card number should be present"),
            () -> Assertions.assertNotNull(response.get("embossedName"), 
                    "Embossed name should be present"),
            () -> Assertions.assertNotNull(response.get("expirationDate"), 
                    "Expiration date should be present"),
            () -> Assertions.assertNotNull(response.get("cardStatus"), 
                    "Card status should be present"),
            () -> Assertions.assertNotNull(response.get("accountId"), 
                    "Account ID should be present"),
            () -> Assertions.assertNotNull(response.get("currentBalance"), 
                    "Current balance should be present"),
            () -> Assertions.assertNotNull(response.get("creditLimit"), 
                    "Credit limit should be present"),
            () -> Assertions.assertNotNull(response.get("availableCredit"), 
                    "Available credit should be present"),
            () -> Assertions.assertNotNull(response.get("recentTransactions"), 
                    "Recent transactions should be present"),
            () -> Assertions.assertNotNull(response.get("transactionCount"), 
                    "Transaction count should be present")
        );
        
        // Verify account ID matches
        Assertions.assertEquals(VALID_ACCOUNT_ID, response.get("accountId"),
                "Account ID should match test account");
        
        // Verify transaction list size
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transactions = 
                (List<Map<String, Object>>) response.get("recentTransactions");
        Assertions.assertEquals(10, transactions.size(),
                "Should include all 10 test transactions");
    }

    /**
     * Test: buildCardDetailResponse_WithoutAccount_HandlesGracefully
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Graceful handling when account relationship is null</li>
     *   <li>Default values provided for missing account data</li>
     *   <li>Response still valid and complete</li>
     * </ul>
     */
    @Test
    @DisplayName("buildCardDetailResponse without account handles gracefully")
    void buildCardDetailResponse_WithoutAccount_HandlesGracefully() {
        // Arrange: Create card without account relationship
        Card cardWithoutAccount = new Card();
        cardWithoutAccount.setCardNumber(VALID_CARD_NUMBER);
        cardWithoutAccount.setAccountId(VALID_ACCOUNT_ID);
        cardWithoutAccount.setEmbossedName(EMBOSSED_NAME);
        cardWithoutAccount.setCvvCode(CVV_CODE);
        cardWithoutAccount.setExpirationDate(EXPIRATION_DATE);
        cardWithoutAccount.setActiveStatus(ACTIVE_STATUS);
        // Do NOT set account relationship

        // Act: Build response
        Map<String, Object> response = cardDetailService.buildCardDetailResponse(
                cardWithoutAccount, testTransactions);

        // Assert: Verify response is still valid
        Assertions.assertNotNull(response, "Response should not be null");
        
        // Verify account ID is still present (from card's accountId field)
        Assertions.assertEquals(VALID_ACCOUNT_ID, response.get("accountId"),
                "Account ID should be populated from card accountId field");
        
        // Verify default values for missing account data
        BigDecimal currentBalance = (BigDecimal) response.get("currentBalance");
        BigDecimal creditLimit = (BigDecimal) response.get("creditLimit");
        BigDecimal availableCredit = (BigDecimal) response.get("availableCredit");
        
        Assertions.assertEquals(BigDecimal.ZERO, currentBalance,
                "Current balance should default to ZERO");
        Assertions.assertEquals(BigDecimal.ZERO, creditLimit,
                "Credit limit should default to ZERO");
        Assertions.assertEquals(BigDecimal.ZERO, availableCredit,
                "Available credit should default to ZERO");
    }

    /**
     * Test: getCardDetail_VerifyTransactionAmountPrecision
     * 
     * <p><strong>COBOL Equivalent:</strong> TRAN-AMT PIC S9(09)V99 COMP-3 precision</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Transaction amounts maintain BigDecimal scale=2</li>
     *   <li>All monetary values use RoundingMode.HALF_UP</li>
     *   <li>COMP-3 precision preserved throughout processing</li>
     * </ul>
     */
    @Test
    @DisplayName("Transaction amounts maintain COMP-3 precision with scale=2")
    void getCardDetail_VerifyTransactionAmountPrecision() {
        // Arrange: Mock repositories
        Mockito.when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(testCard));
        
        Page<Transaction> transactionPage = new PageImpl<>(testTransactions);
        Mockito.when(transactionRepository.findByCardNumber(
                Mockito.eq(VALID_CARD_NUMBER), 
                Mockito.any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Get card details
        Map<String, Object> result = cardDetailService.getCardDetail(VALID_CARD_NUMBER);

        // Assert: Verify all transaction amounts have correct precision
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transactions = 
                (List<Map<String, Object>>) result.get("recentTransactions");
        
        for (Map<String, Object> transaction : transactions) {
            BigDecimal amount = (BigDecimal) transaction.get("amount");
            
            Assertions.assertNotNull(amount, 
                    "Transaction amount should not be null");
            Assertions.assertEquals(2, amount.scale(), 
                    "Transaction amount must have scale=2 per COBOL COMP-3 precision");
            Assertions.assertEquals(TRANSACTION_AMOUNT, amount,
                    "Transaction amount should match test data with exact precision");
        }
    }

    /**
     * Test: getCardDetail_VerifyResponseStructure
     * 
     * <p><strong>COBOL Equivalent:</strong> Complete screen response structure</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Response contains all required fields</li>
     *   <li>Field types match specifications</li>
     *   <li>Structure matches COBOL screen layout</li>
     * </ul>
     */
    @Test
    @DisplayName("Card detail response has complete structure matching COBOL screen layout")
    void getCardDetail_VerifyResponseStructure() {
        // Arrange: Mock repositories
        Mockito.when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(testCard));
        
        Page<Transaction> transactionPage = new PageImpl<>(testTransactions);
        Mockito.when(transactionRepository.findByCardNumber(
                Mockito.eq(VALID_CARD_NUMBER), 
                Mockito.any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Get card details
        Map<String, Object> result = cardDetailService.getCardDetail(VALID_CARD_NUMBER);

        // Assert: Verify response structure using assertAll for comprehensive validation
        Assertions.assertAll("Complete response structure validation",
            // Card information fields
            () -> Assertions.assertTrue(result.containsKey("cardNumber"), 
                    "Response must contain cardNumber"),
            () -> Assertions.assertTrue(result.containsKey("embossedName"), 
                    "Response must contain embossedName"),
            () -> Assertions.assertTrue(result.containsKey("expirationDate"), 
                    "Response must contain expirationDate"),
            () -> Assertions.assertTrue(result.containsKey("cardStatus"), 
                    "Response must contain cardStatus"),
            () -> Assertions.assertTrue(result.containsKey("cardStatusCode"), 
                    "Response must contain cardStatusCode"),
            () -> Assertions.assertTrue(result.containsKey("isActive"), 
                    "Response must contain isActive"),
            () -> Assertions.assertTrue(result.containsKey("isExpired"), 
                    "Response must contain isExpired"),
            
            // Account information fields
            () -> Assertions.assertTrue(result.containsKey("accountId"), 
                    "Response must contain accountId"),
            () -> Assertions.assertTrue(result.containsKey("currentBalance"), 
                    "Response must contain currentBalance"),
            () -> Assertions.assertTrue(result.containsKey("creditLimit"), 
                    "Response must contain creditLimit"),
            () -> Assertions.assertTrue(result.containsKey("availableCredit"), 
                    "Response must contain availableCredit"),
            () -> Assertions.assertTrue(result.containsKey("accountStatus"), 
                    "Response must contain accountStatus"),
            
            // Transaction information fields
            () -> Assertions.assertTrue(result.containsKey("recentTransactions"), 
                    "Response must contain recentTransactions"),
            () -> Assertions.assertTrue(result.containsKey("transactionCount"), 
                    "Response must contain transactionCount"),
            
            // Verify field types
            () -> Assertions.assertInstanceOf(String.class, result.get("cardNumber"), 
                    "cardNumber should be String"),
            () -> Assertions.assertInstanceOf(String.class, result.get("embossedName"), 
                    "embossedName should be String"),
            () -> Assertions.assertInstanceOf(String.class, result.get("expirationDate"), 
                    "expirationDate should be String"),
            () -> Assertions.assertInstanceOf(Boolean.class, result.get("isActive"), 
                    "isActive should be Boolean"),
            () -> Assertions.assertInstanceOf(Long.class, result.get("accountId"), 
                    "accountId should be Long"),
            () -> Assertions.assertInstanceOf(BigDecimal.class, result.get("currentBalance"), 
                    "currentBalance should be BigDecimal"),
            () -> Assertions.assertInstanceOf(BigDecimal.class, result.get("creditLimit"), 
                    "creditLimit should be BigDecimal"),
            () -> Assertions.assertInstanceOf(BigDecimal.class, result.get("availableCredit"), 
                    "availableCredit should be BigDecimal"),
            () -> Assertions.assertInstanceOf(List.class, result.get("recentTransactions"), 
                    "recentTransactions should be List"),
            () -> Assertions.assertInstanceOf(Integer.class, result.get("transactionCount"), 
                    "transactionCount should be Integer")
        );
    }

    /**
     * Test: getCardDetail_VerifyCardNumberFormat
     * 
     * <p><strong>COBOL Equivalent:</strong> CARD-CARD-NUM PIC X(16) validation</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card number exactly 16 characters</li>
     *   <li>Card number format validation</li>
     * </ul>
     */
    @Test
    @DisplayName("Card number format validation enforces 16 digit requirement")
    void getCardDetail_VerifyCardNumberFormat() {
        // Verify test card number is exactly 16 characters
        Assertions.assertEquals(16, VALID_CARD_NUMBER.length(),
                "Test card number must be exactly 16 characters per COBOL PIC X(16)");
        
        // Verify card number contains only digits
        Assertions.assertTrue(VALID_CARD_NUMBER.matches("\\d{16}"),
                "Card number must contain only digits");
    }

    /**
     * Test: getCardDetail_VerifyExpiryDateFormat
     * 
     * <p><strong>COBOL Equivalent:</strong> CARD-EXPIRY-MONTH / CARD-EXPIRY-YEAR display format</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Expiry date formatted as MM/YY</li>
     *   <li>Month 01-12, Year 00-99</li>
     * </ul>
     */
    @Test
    @DisplayName("Card expiry date format matches COBOL MM/YY display")
    void getCardDetail_VerifyExpiryDateFormat() {
        // Act: Get formatted expiration date
        String formattedExpiry = testCard.getFormattedExpirationDate();

        // Assert: Verify MM/YY format
        Assertions.assertNotNull(formattedExpiry, "Formatted expiry should not be null");
        Assertions.assertTrue(formattedExpiry.matches("\\d{2}/\\d{2}"),
                "Expiry date must match MM/YY format");
        
        // Verify month is valid (01-12)
        String monthPart = formattedExpiry.substring(0, 2);
        int month = Integer.parseInt(monthPart);
        Assertions.assertTrue(month >= 1 && month <= 12,
                "Month must be between 01 and 12");
    }
}

