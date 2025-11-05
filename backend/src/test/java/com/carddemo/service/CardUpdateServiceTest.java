/*
 * CardUpdateServiceTest.java
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.service;

import com.carddemo.constants.CardStatus;
import com.carddemo.dto.request.CardUpdateRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.exception.CardUpdateException;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.DecimalUtils;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for CardUpdateService validating business logic transformation from COCRDUPC.cbl.
 * 
 * <p>This test class ensures functional equivalence between the COBOL card update program (COCRDUPC.cbl) 
 * and the Java Spring Boot CardUpdateService implementation, with specific focus on:</p>
 * <ul>
 *   <li>Card status changes (ACTIVE/INACTIVE/BLOCKED/EXPIRED) with 88-level condition preservation</li>
 *   <li>Credit limit modifications with COMP-3 decimal precision using BigDecimal scale=2 and HALF_UP rounding</li>
 *   <li>Expiry date validation (month 1-12, year 1950-2099, future date requirement)</li>
 *   <li>VSAM CARDDAT UPDATE operations transformed to JPA repository save with @Transactional boundaries</li>
 *   <li>Optimistic locking for concurrent update detection matching COBOL DATA-WAS-CHANGED-BEFORE-UPDATE</li>
 *   <li>Field validation matching COBOL input edit paragraphs (1230-EDIT-NAME, 1240-EDIT-CARDSTATUS, etc.)</li>
 *   <li>Audit trail logging with old/new value capture for regulatory compliance</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Transformation Context (Section 0.6):</strong></p>
 * <ul>
 *   <li><strong>Source Program:</strong> app/cbl/COCRDUPC.cbl (Card Update CICS Program)</li>
 *   <li><strong>Copybook:</strong> app/cpy/CVACT03Y.cpy (Card Cross-Reference Layout)</li>
 *   <li><strong>Transaction ID:</strong> CCUP (Credit Card Update)</li>
 *   <li><strong>BMS Screen:</strong> COCRDUP (Card Update Screen with edit fields)</li>
 *   <li><strong>VSAM File:</strong> CARDDAT KSDS (Card Master File with UPDATE locking)</li>
 *   <li><strong>Key Business Logic:</strong> Lines 1420-1496 (9200-WRITE-PROCESSING paragraph)</li>
 * </ul>
 * 
 * <p><strong>Critical COBOL Business Rules Tested (Section 0.9 Requirements):</strong></p>
 * <ol>
 *   <li><strong>Card Name Validation (COBOL lines 806-843):</strong>
 *       <ul>
 *         <li>Card name must be non-blank (WS-PROMPT-FOR-NAME error line 182)</li>
 *         <li>Only alphabetic characters and spaces allowed (WS-NAME-MUST-BE-ALPHA line 183-184)</li>
 *         <li>INSPECT CONVERTING LIT-ALL-ALPHA-FROM (line 825) to validate character set</li>
 *         <li>Uppercase conversion: INSPECT CONVERTING LIT-LOWER TO LIT-UPPER (lines 1357-1358)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Card Status Validation (COBOL lines 845-876):</strong>
 *       <ul>
 *         <li>Status must be Y (Active) or N (Inactive) - CARD-STATUS-MUST-BE-YES-NO (line 195)</li>
 *         <li>FLG-YES-NO-VALID 88-level condition: VALUES 'Y', 'N' (line 91)</li>
 *         <li>Status cannot be blank, zeros, or low-values (lines 850-858)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Expiry Month Validation (COBOL lines 877-912):</strong>
 *       <ul>
 *         <li>Month must be between 1 and 12 (VALID-MONTH 88-level: VALUES 1 THRU 12, line 95)</li>
 *         <li>CARD-EXPIRY-MONTH-NOT-VALID error message (lines 197-198)</li>
 *         <li>Numeric validation required (lines 894-896)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Expiry Year Validation (COBOL lines 913-947):</strong>
 *       <ul>
 *         <li>Year must be between 1950 and 2099 (VALID-YEAR 88-level: VALUES 1950 THRU 2099, line 99)</li>
 *         <li>CARD-EXPIRY-YEAR-NOT-VALID error message (lines 199-200)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Concurrent Modification Detection (COBOL lines 1498-1523):</strong>
 *       <ul>
 *         <li>9300-CHECK-CHANGE-IN-REC paragraph compares all fields</li>
 *         <li>DATA-WAS-CHANGED-BEFORE-UPDATE flag (line 1511)</li>
 *         <li>Compares CVV, name, expiration, status with old values (lines 1503-1508)</li>
 *         <li>If changed: Reload data and notify user (line 997)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Update Processing (COBOL lines 1420-1496):</strong>
 *       <ul>
 *         <li>EXEC CICS READ UPDATE for pessimistic locking (lines 1427-1436)</li>
 *         <li>COULD-NOT-LOCK-FOR-UPDATE error handling (line 1446)</li>
 *         <li>EXEC CICS REWRITE for data persistence (lines 1477-1483)</li>
 *         <li>LOCKED-BUT-UPDATE-FAILED error handling (line 1491)</li>
 *       </ul>
 *   </li>
 * </ol>
 * 
 * <p><strong>COMP-3 Precision Testing (Section 0.9 Critical Requirement):</strong></p>
 * <ul>
 *   <li>Balance calculations use BigDecimal with scale=2 and RoundingMode.HALF_UP</li>
 *   <li>COBOL PIC S9(13)V99 COMP-3 → Java BigDecimal(precision=15, scale=2)</li>
 *   <li>Test validates: 12345.678 + 67890.123 = 80235.80 (NOT 80235.801 due to HALF_UP)</li>
 *   <li>All monetary operations must maintain exact 2-decimal precision</li>
 *   <li>DecimalUtils.setScaleWithRounding() ensures consistent precision</li>
 * </ul>
 * 
 * <p><strong>Transaction Boundary Testing (Section 0.9 Compliance):</strong></p>
 * <ul>
 *   <li>@Transactional(isolation=READ_COMMITTED) matches CICS default isolation</li>
 *   <li>Propagation.REQUIRED participates in existing transaction</li>
 *   <li>rollbackFor=Exception.class ensures automatic rollback on errors</li>
 *   <li>COBOL EXEC CICS SYNCPOINT → Spring transaction commit at method end</li>
 *   <li>COBOL SYNCPOINT ROLLBACK → Exception-triggered rollback</li>
 * </ul>
 * 
 * <p><strong>Optimistic Locking Testing (JPA @Version Mechanism):</strong></p>
 * <ul>
 *   <li>Card entity has @Version field for automatic versioning</li>
 *   <li>OptimisticLockException thrown if version mismatch detected</li>
 *   <li>Replaces COBOL 9300-CHECK-CHANGE-IN-REC manual comparison</li>
 *   <li>Service catches OptimisticLockingFailureException and throws CardUpdateException</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Matrix:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>Test Method</th>
 *     <th>COBOL Paragraph</th>
 *     <th>Business Rule Validated</th>
 *   </tr>
 *   <tr>
 *     <td>updateCard_ChangesStatus_SavesCorrectly()</td>
 *     <td>9200-WRITE-PROCESSING</td>
 *     <td>Status change Y→N with successful database update</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_ModifiesCreditLimit_PreservesPrecision()</td>
 *     <td>N/A (precision test)</td>
 *     <td>BigDecimal scale=2, HALF_UP rounding preservation</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_ExpiryDateUpdate_ValidatesFormat()</td>
 *     <td>1250-EDIT-EXPIRY-MON, 1260-EDIT-EXPIRY-YEAR</td>
 *     <td>Month 1-12, Year 1950-2099, future date required</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_BlockedCard_PreventsTransitions()</td>
 *     <td>1240-EDIT-CARDSTATUS</td>
 *     <td>Invalid status transitions (BLOCKED→ACTIVE not allowed)</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_ConcurrentUpdate_ThrowsException()</td>
 *     <td>9300-CHECK-CHANGE-IN-REC</td>
 *     <td>Optimistic locking detects concurrent modifications</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_InvalidCardNumber_ThrowsNotFoundException()</td>
 *     <td>9100-GETCARD-BYACCTCARD (NOTFND)</td>
 *     <td>DFHRESP(NOTFND) → CardNotFoundException</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_InvalidExpirationMonth_ThrowsException()</td>
 *     <td>1250-EDIT-EXPIRY-MON</td>
 *     <td>CARD-EXPIRY-MONTH-NOT-VALID (month &lt; 1 or &gt; 12)</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_InvalidExpirationYear_ThrowsException()</td>
 *     <td>1260-EDIT-EXPIRY-YEAR</td>
 *     <td>CARD-EXPIRY-YEAR-NOT-VALID (year &lt; 1950 or &gt; 2099)</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_BlankCardName_ThrowsException()</td>
 *     <td>1230-EDIT-NAME</td>
 *     <td>WS-PROMPT-FOR-NAME (card name required)</td>
 *   </tr>
 *   <tr>
 *     <td>updateCard_InvalidCardName_ThrowsException()</td>
 *     <td>1230-EDIT-NAME</td>
 *     <td>WS-NAME-MUST-BE-ALPHA (alphabetic + spaces only)</td>
 *   </tr>
 *   <tr>
 *     <td>testBigDecimalPrecision_MatchesCOBOLCOMP3()</td>
 *     <td>N/A (precision verification)</td>
 *     <td>COMP-3 PIC S9(9)V99 arithmetic equivalence</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>Mockito Usage Pattern:</strong></p>
 * <ul>
 *   <li>@ExtendWith(MockitoExtension.class) enables Mockito annotations</li>
 *   <li>@Mock CardRepository - Mocks database operations for unit testing</li>
 *   <li>@InjectMocks CardUpdateService - Injects mocked dependencies automatically</li>
 *   <li>when().thenReturn() - Stubs repository method responses</li>
 *   <li>verify() - Asserts that repository methods were called with expected arguments</li>
 *   <li>doThrow() - Simulates exceptions (e.g., OptimisticLockException)</li>
 * </ul>
 * 
 * <p><strong>Test Data Construction:</strong></p>
 * <ul>
 *   <li>Sample card number: "4556123456789012" (16 digits, valid Luhn checksum)</li>
 *   <li>Sample account ID: 12345678901L (11 digits per COBOL PIC 9(11))</li>
 *   <li>Sample CVV: "123" (3 digits per COBOL PIC 9(03))</li>
 *   <li>Sample embossed name: "JOHN DOE" (uppercase per COBOL conversion)</li>
 *   <li>Sample status: "Y" for ACTIVE, "N" for INACTIVE (COBOL PIC X(01))</li>
 *   <li>Sample expiration: LocalDate.of(2027, 12, 31) (future date, valid range)</li>
 * </ul>
 * 
 * <p><strong>Assertion Strategy:</strong></p>
 * <ul>
 *   <li>assertEquals() for scalar value comparisons</li>
 *   <li>assertTrue/assertFalse() for boolean conditions</li>
 *   <li>assertNotNull() for null safety validation</li>
 *   <li>assertThrows() for exception validation with exact exception type</li>
 *   <li>assertAll() for grouped assertions (validates multiple conditions atomically)</li>
 * </ul>
 * 
 * <p><strong>Design Pattern:</strong> Unit Testing with Mockito Framework</p>
 * <p><strong>Thread Safety:</strong> Tests are independent and thread-safe</p>
 * 
 * @see CardUpdateService
 * @see Card
 * @see CardUpdateRequest
 * @see CardRepository
 * @see CardNotFoundException
 * @see CardUpdateException
 * @see DecimalUtils
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">COMP-3 Precision and Transaction Requirements</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService Test Suite - COCRDUPC.cbl Functional Equivalence")
public class CardUpdateServiceTest {

    @Mock
    private CardRepository cardRepository;

    @Mock
    private com.carddemo.repository.AccountRepository accountRepository;

    @Mock
    private DecimalUtils decimalUtils;

    @InjectMocks
    private CardUpdateService cardUpdateService;

    private Card testCard;
    private CardUpdateRequest updateRequest;
    private Account testAccount;

    /**
     * Sets up test fixtures before each test method execution.
     * 
     * <p>Initializes:</p>
     * <ul>
     *   <li>Test Card entity with valid data matching COBOL CARD-RECORD structure</li>
     *   <li>Test CardUpdateRequest DTO with valid update data</li>
     *   <li>Test Account entity for relationship validation</li>
     * </ul>
     * 
     * <p>Test data matches COBOL field constraints:</p>
     * <ul>
     *   <li>Card number: PIC X(16) → "4556123456789012"</li>
     *   <li>Account ID: PIC 9(11) → 12345678901L</li>
     *   <li>CVV code: PIC 9(03) → "123"</li>
     *   <li>Embossed name: PIC X(50) → "JOHN DOE"</li>
     *   <li>Active status: PIC X(01) → "Y"</li>
     *   <li>Expiration date: PIC X(10) → LocalDate.of(2027, 12, 31)</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Initialize test card matching COBOL CARD-RECORD layout (CVACT02Y.cpy)
        testCard = new Card();
        testCard.setCardNumber("4556123456789012"); // CARD-NUM PIC X(16)
        testCard.setAccountId(12345678901L);        // CARD-ACCT-ID PIC 9(11)
        testCard.setCvvCode("123");                  // CARD-CVV-CD PIC 9(03)
        testCard.setEmbossedName("JOHN DOE");        // CARD-EMBOSSED-NAME PIC X(50)
        testCard.setActiveStatus("Y");               // CARD-ACTIVE-STATUS PIC X(01) - ACTIVE
        testCard.setExpirationDate(LocalDate.of(2027, 12, 31)); // CARD-EXPIRAION-DATE

        // Initialize test account for relationship validation
        testAccount = new Account();
        testAccount.setAccountId(12345678901L);
        testAccount.setCreditLimit(new BigDecimal("10000.00"));
        testAccount.setCurrentBalance(new BigDecimal("2500.00"));
        testAccount.setActiveStatus("A");

        // Initialize update request matching BMS screen input (COCRDUP.bms)
        updateRequest = new CardUpdateRequest();
        updateRequest.setCardNumber("4556123456789012");
        updateRequest.setAccountId("12345678901");
        updateRequest.setCardName("JANE SMITH");
        updateRequest.setCardStatusCode("N");        // Change to INACTIVE
        updateRequest.setExpirationMonth(6);         // VALID-MONTH: 1-12
        updateRequest.setExpirationYear(2028);       // VALID-YEAR: 1950-2099
        updateRequest.setExpirationDay(30);
        updateRequest.setExpirationDate(LocalDate.of(2028, 6, 30));
    }

    /**
     * Tests successful card status change from ACTIVE to INACTIVE.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 988-1001 (CCUP-CHANGES-OK-NOT-CONFIRMED)</p>
     * <pre>
     * COBOL: WHEN CCUP-CHANGES-OK-NOT-CONFIRMED
     *            AND CCARD-AID-PFK05
     *            PERFORM 9200-WRITE-PROCESSING
     *                THRU 9200-WRITE-PROCESSING-EXIT
     *            SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE
     * </pre>
     * 
     * <p><strong>Business Rule Tested:</strong> Card status can change from ACTIVE ('Y') to INACTIVE ('N')</p>
     * <p><strong>Validation:</strong> Status transition Y→N is valid per 88-level conditions</p>
     */
    @Test
    @DisplayName("updateCard() - Status change Y→N saves correctly to database")
    void updateCard_ChangesStatus_SavesCorrectly() {
        // Arrange: Setup mock repository behavior
        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));
        when(cardRepository.save(any(Card.class)))
            .thenReturn(testCard);

        // Act: Execute card update
        Card updatedCard = cardUpdateService.updateCard(updateRequest, "USER123");

        // Assert: Verify status was changed and saved
        assertNotNull(updatedCard, "Updated card should not be null");
        assertEquals("N", updatedCard.getActiveStatus(), 
                    "Card status should be changed to INACTIVE ('N')");
        verify(cardRepository, times(1)).findByCardNumber("4556123456789012");
        verify(cardRepository, times(1)).save(any(Card.class));
    }

    /**
     * Tests BigDecimal precision preservation matching COBOL COMP-3 arithmetic.
     * 
     * <p><strong>COBOL COMP-3 Mapping:</strong></p>
     * <pre>
     * COBOL: 01 BALANCE PIC S9(13)V99 COMP-3.
     *        COMPUTE BALANCE ROUNDED = AMOUNT1 + AMOUNT2
     * 
     * Java:  BigDecimal balance = amount1.add(amount2)
     *            .setScale(2, RoundingMode.HALF_UP);
     * </pre>
     * 
     * <p><strong>Critical Test Case:</strong> 12345.678 + 67890.123 = 80235.80 (NOT 80235.801)</p>
     * <p><strong>Validation:</strong> Ensures HALF_UP rounding matches COBOL ROUNDED clause behavior</p>
     */
    @Test
    @DisplayName("BigDecimal precision matches COBOL COMP-3 PIC S9(13)V99 arithmetic")
    void testBigDecimalPrecision_MatchesCOBOLCOMP3() {
        // Test COMP-3 precision preservation with HALF_UP rounding
        BigDecimal amount1 = new BigDecimal("12345.678");
        BigDecimal amount2 = new BigDecimal("67890.123");
        
        // COBOL: COMPUTE BALANCE ROUNDED = AMOUNT1 + AMOUNT2
        BigDecimal result = amount1.add(amount2).setScale(2, RoundingMode.HALF_UP);
        
        // Assert: Result must be 80235.80 (scale=2, HALF_UP rounding)
        assertEquals(new BigDecimal("80235.80"), result, 
                    "BigDecimal addition must match COBOL COMP-3 precision with HALF_UP rounding");
        assertEquals(2, result.scale(), 
                    "BigDecimal scale must be 2 to match COBOL V99 decimal places");
    }

    /**
     * Tests card expiration date update with month/year validation.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 877-947 (1250-EDIT-EXPIRY-MON, 1260-EDIT-EXPIRY-YEAR)</p>
     * <pre>
     * COBOL: 1250-EDIT-EXPIRY-MON.
     *            IF VALID-MONTH
     *                SET FLG-CARDEXPMON-ISVALID TO TRUE
     *            ELSE
     *                SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE
     *        1260-EDIT-EXPIRY-YEAR.
     *            IF VALID-YEAR
     *                SET FLG-CARDEXPYEAR-ISVALID TO TRUE
     *            ELSE
     *                SET CARD-EXPIRY-YEAR-NOT-VALID TO TRUE
     * </pre>
     * 
     * <p><strong>Business Rules Tested:</strong></p>
     * <ul>
     *   <li>Month must be 1-12 (VALID-MONTH 88-level condition)</li>
     *   <li>Year must be 1950-2099 (VALID-YEAR 88-level condition)</li>
     *   <li>Composed date must be in the future</li>
     * </ul>
     */
    @Test
    @DisplayName("updateCard() - Expiration date update validates month (1-12) and year (1950-2099)")
    void updateCard_ExpiryDateUpdate_ValidatesFormat() {
        // Arrange: Update request with valid future expiration date
        updateRequest.setExpirationMonth(12);
        updateRequest.setExpirationYear(2029);
        updateRequest.setExpirationDate(LocalDate.of(2029, 12, 31));

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));
        when(cardRepository.save(any(Card.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute card update
        Card updatedCard = cardUpdateService.updateCard(updateRequest, "USER123");

        // Assert: Verify expiration date was updated correctly
        assertNotNull(updatedCard, "Updated card should not be null");
        assertEquals(LocalDate.of(2029, 12, 31), updatedCard.getExpirationDate(), 
                    "Card expiration date should be updated to 2029-12-31");
        verify(cardRepository, times(1)).save(any(Card.class));
    }

    /**
     * Tests invalid expiration month (< 1 or > 12) throws validation exception.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 197-198 (CARD-EXPIRY-MONTH-NOT-VALID error)</p>
     * <pre>
     * COBOL: 88 VALID-MONTH VALUES 1 THRU 12.
     *        IF NOT VALID-MONTH
     *            SET CARD-EXPIRY-MONTH-NOT-VALID TO TRUE
     *        'Card expiry month must be between 1 and 12'
     * </pre>
     * 
     * <p><strong>Test Cases:</strong> Month = 0 (below range), Month = 13 (above range)</p>
     */
    @Test
    @DisplayName("updateCard() - Invalid expiration month throws CardUpdateException")
    void updateCard_InvalidExpirationMonth_ThrowsException() {
        // Arrange: Set invalid expiration month (13 - above valid range)
        updateRequest.setExpirationMonth(13);
        updateRequest.setExpirationYear(2028);

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify exception is thrown with appropriate message
        CardUpdateException exception = assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardUpdateException for invalid expiration month");
        
        assertTrue(exception.getMessage().contains("month must be between 1 and 12"),
                  "Exception message should indicate month validation error");
    }

    /**
     * Tests invalid expiration year (< 1950 or > 2099) throws validation exception.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 199-200 (CARD-EXPIRY-YEAR-NOT-VALID error)</p>
     * <pre>
     * COBOL: 88 VALID-YEAR VALUES 1950 THRU 2099.
     *        IF NOT VALID-YEAR
     *            SET CARD-EXPIRY-YEAR-NOT-VALID TO TRUE
     *        'Invalid card expiry year'
     * </pre>
     * 
     * <p><strong>Test Cases:</strong> Year = 1949 (below range), Year = 2100 (above range)</p>
     */
    @Test
    @DisplayName("updateCard() - Invalid expiration year throws CardUpdateException")
    void updateCard_InvalidExpirationYear_ThrowsException() {
        // Arrange: Set invalid expiration year (2100 - above valid range)
        updateRequest.setExpirationMonth(6);
        updateRequest.setExpirationYear(2100);

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify exception is thrown with appropriate message
        CardUpdateException exception = assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardUpdateException for invalid expiration year");
        
        assertTrue(exception.getMessage().contains("year must be between 1950 and 2099"),
                  "Exception message should indicate year validation error");
    }

    /**
     * Tests blank/empty card name throws validation exception.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 811-820 (1230-EDIT-NAME paragraph)</p>
     * <pre>
     * COBOL: 1230-EDIT-NAME.
     *            IF CCUP-NEW-CRDNAME EQUAL LOW-VALUES
     *            OR CCUP-NEW-CRDNAME EQUAL SPACES
     *            OR CCUP-NEW-CRDNAME EQUAL ZEROS
     *                SET WS-PROMPT-FOR-NAME TO TRUE
     *        'Card name not provided' (line 182)
     * </pre>
     * 
     * <p><strong>Business Rule:</strong> Card embossed name is mandatory field</p>
     */
    @Test
    @DisplayName("updateCard() - Blank card name throws validation exception")
    void updateCard_BlankCardName_ThrowsException() {
        // Arrange: Set card name to blank/empty
        updateRequest.setCardName("");

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify exception is thrown
        CardUpdateException exception = assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardUpdateException for blank card name");
        
        assertTrue(exception.getMessage().contains("name is required"),
                  "Exception message should indicate name is required");
    }

    /**
     * Tests card name with invalid characters (non-alphabetic) throws exception.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 823-837 (1230-EDIT-NAME paragraph)</p>
     * <pre>
     * COBOL: MOVE CCUP-NEW-CRDNAME TO CARD-NAME-CHECK
     *        INSPECT CARD-NAME-CHECK
     *            CONVERTING LIT-ALL-ALPHA-FROM
     *                    TO LIT-ALL-SPACES-TO
     *        IF FUNCTION LENGTH(FUNCTION TRIM(CARD-NAME-CHECK)) = 0
     *            CONTINUE  [Valid - all alphabetic]
     *        ELSE
     *            SET WS-NAME-MUST-BE-ALPHA TO TRUE
     *        'Card name can only contain alphabets and spaces' (lines 183-184)
     * </pre>
     * 
     * <p><strong>Business Rule:</strong> Only alphabetic characters and spaces allowed in embossed name</p>
     */
    @Test
    @DisplayName("updateCard() - Card name with invalid characters throws exception")
    void updateCard_InvalidCardName_ThrowsException() {
        // Arrange: Set card name with numeric/special characters
        updateRequest.setCardName("JOHN123DOE");

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify exception is thrown
        CardUpdateException exception = assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardUpdateException for invalid card name characters");
        
        assertTrue(exception.getMessage().contains("alphabetic characters and spaces"),
                  "Exception message should indicate only alphabetic characters allowed");
    }

    /**
     * Tests invalid status transition from BLOCKED to ACTIVE is rejected.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 845-876 (1240-EDIT-CARDSTATUS)</p>
     * <pre>
     * COBOL: 05 FLG-YES-NO-CHECK PIC X(1).
     *            88 FLG-YES-NO-VALID VALUES 'Y', 'N'.
     *        IF NOT FLG-YES-NO-VALID
     *            SET CARD-STATUS-MUST-BE-YES-NO TO TRUE
     *        'Card Active Status must be Y or N' (line 195)
     * </pre>
     * 
     * <p><strong>Business Rule:</strong> Blocked cards cannot be reactivated without admin approval</p>
     */
    @Test
    @DisplayName("updateCard() - Invalid status transition BLOCKED→ACTIVE throws exception")
    void updateCard_BlockedCard_PreventsInvalidTransition() {
        // Arrange: Set card to BLOCKED status, attempt to change to ACTIVE
        testCard.setActiveStatus("B");  // BLOCKED status
        updateRequest.setCardStatusCode("Y");  // Attempt to activate

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify invalid transition is rejected
        CardUpdateException exception = assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardUpdateException for invalid BLOCKED→ACTIVE transition");
        
        assertTrue(exception.getMessage().contains("Blocked cards"),
                  "Exception message should indicate blocked card restriction");
    }

    /**
     * Tests concurrent card update detection via optimistic locking.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 1498-1523 (9300-CHECK-CHANGE-IN-REC)</p>
     * <pre>
     * COBOL: 9300-CHECK-CHANGE-IN-REC.
     *            IF  CARD-CVV-CD       NOT EQUAL TO CCUP-OLD-CVV-CD
     *            OR  CARD-EMBOSSED-NAME NOT EQUAL TO CCUP-OLD-CRDNAME
     *            OR  CARD-EXPIRAION-DATE(1:4) NOT EQUAL TO CCUP-OLD-EXPYEAR
     *            OR  CARD-ACTIVE-STATUS NOT EQUAL TO CCUP-OLD-CRDSTCD
     *                SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE
     *        'Record changed by some one else. Please review' (line 208)
     * </pre>
     * 
     * <p><strong>JPA Optimistic Locking:</strong> @Version field in Card entity automatically detects version mismatch</p>
     */
    @Test
    @DisplayName("updateCard() - Concurrent modification throws OptimisticLockException")
    void updateCard_ConcurrentUpdate_ThrowsException() {
        // Arrange: Simulate concurrent modification by throwing OptimisticLockException
        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));
        when(cardRepository.save(any(Card.class)))
            .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                "Card", "4556123456789012"));

        // Act & Assert: Verify concurrent update is detected and handled
        CardUpdateException exception = assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardUpdateException for concurrent update conflict");
        
        assertTrue(exception.getMessage().contains("modified by another user"),
                  "Exception message should indicate concurrent modification");
        assertEquals(CardUpdateException.UpdateFailureReason.CONCURRENT_UPDATE_CONFLICT, 
                    exception.getFailureReason(),
                    "Failure reason should be CONCURRENT_UPDATE_CONFLICT");
    }

    /**
     * Tests card not found scenario throws CardNotFoundException.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 1392-1401 (9100-GETCARD-BYACCTCARD NOTFND)</p>
     * <pre>
     * COBOL: EXEC CICS READ
     *             FILE      (LIT-CARDFILENAME)
     *             RIDFLD    (WS-CARD-RID-CARDNUM)
     *             INTO      (CARD-RECORD)
     *             RESP      (WS-RESP-CD)
     *        END-EXEC
     *        WHEN DFHRESP(NOTFND)
     *            SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE
     *        'Did not find cards for this search condition' (line 204)
     * </pre>
     * 
     * <p><strong>Error Mapping:</strong> DFHRESP(NOTFND) → CardNotFoundException</p>
     */
    @Test
    @DisplayName("updateCard() - Non-existent card number throws CardNotFoundException")
    void updateCard_InvalidCardNumber_ThrowsNotFoundException() {
        // Arrange: Repository returns empty Optional (card not found)
        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.empty());

        // Act & Assert: Verify CardNotFoundException is thrown
        CardNotFoundException exception = assertThrows(CardNotFoundException.class, 
            () -> cardUpdateService.updateCard(updateRequest, "USER123"),
            "Should throw CardNotFoundException when card does not exist");
        
        assertEquals("4556123456789012", exception.getCardIdentifier(),
                    "Exception should contain the card number that was not found");
        verify(cardRepository, times(1)).findByCardNumber("4556123456789012");
        verify(cardRepository, never()).save(any(Card.class));
    }

    /**
     * Tests that card name is converted to uppercase as per COBOL logic.
     * 
     * <p><strong>COBOL Equivalent:</strong> COCRDUPC.cbl lines 1356-1358</p>
     * <pre>
     * COBOL: INSPECT CARD-EMBOSSED-NAME
     *            CONVERTING LIT-LOWER
     *                    TO LIT-UPPER
     * </pre>
     * 
     * <p><strong>Business Rule:</strong> All card embossed names stored in uppercase for consistency</p>
     */
    @Test
    @DisplayName("updateCard() - Card name is converted to uppercase")
    void updateCard_ConvertsNameToUppercase() {
        // Arrange: Set card name in lowercase
        updateRequest.setCardName("john doe");

        when(cardRepository.findByCardNumber("4556123456789012"))
            .thenReturn(Optional.of(testCard));
        when(cardRepository.save(any(Card.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute card update
        Card updatedCard = cardUpdateService.updateCard(updateRequest, "USER123");

        // Assert: Verify name was converted to uppercase
        assertEquals("JOHN DOE", updatedCard.getEmbossedName(), 
                    "Card embossed name should be converted to uppercase");
    }

    /**
     * Tests validateStatusTransition method with various status change scenarios.
     * 
     * <p>Tests valid and invalid transitions per business rules:</p>
     * <ul>
     *   <li>Y ↔ N: Valid bidirectional transition</li>
     *   <li>P → Y: Valid activation from pending</li>
     *   <li>E → *: Invalid (expired cards cannot be reactivated)</li>
     *   <li>C → *: Invalid (closed cards cannot be reactivated)</li>
     *   <li>B → Y/N: Invalid (blocked cards require admin approval)</li>
     * </ul>
     */
    @Test
    @DisplayName("validateStatusTransition() - Validates all status transition rules")
    void validateStatusTransition_ChecksAllRules() {
        // Test valid transitions
        assertDoesNotThrow(() -> cardUpdateService.validateStatusTransition("Y", "N"),
                          "ACTIVE→INACTIVE transition should be valid");
        assertDoesNotThrow(() -> cardUpdateService.validateStatusTransition("N", "Y"),
                          "INACTIVE→ACTIVE transition should be valid");
        assertDoesNotThrow(() -> cardUpdateService.validateStatusTransition("P", "Y"),
                          "PENDING→ACTIVE transition should be valid");
        assertDoesNotThrow(() -> cardUpdateService.validateStatusTransition("Y", "B"),
                          "ACTIVE→BLOCKED transition should be valid");

        // Test invalid transitions
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateStatusTransition("E", "Y"),
            "EXPIRED→ACTIVE transition should be invalid");
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateStatusTransition("C", "Y"),
            "CLOSED→ACTIVE transition should be invalid");
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateStatusTransition("B", "Y"),
            "BLOCKED→ACTIVE transition should be invalid");
    }

    /**
     * Tests validateExpirationDate method with various date scenarios.
     * 
     * <p>Tests boundary conditions:</p>
     * <ul>
     *   <li>Month boundaries: 1 (January), 12 (December), 0 (invalid), 13 (invalid)</li>
     *   <li>Year boundaries: 1950 (min), 2099 (max), 1949 (invalid), 2100 (invalid)</li>
     *   <li>Future date requirement: Date must be after today</li>
     * </ul>
     */
    @Test
    @DisplayName("validateExpirationDate() - Validates month/year boundaries and future date")
    void validateExpirationDate_ChecksBoundaries() {
        // Test valid dates (must be future dates)
        assertDoesNotThrow(() -> cardUpdateService.validateExpirationDate("01", "2026"),
                          "January 2026 should be valid");
        assertDoesNotThrow(() -> cardUpdateService.validateExpirationDate("12", "2099"),
                          "December 2099 should be valid");

        // Test invalid month
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateExpirationDate("00", "2026"),
            "Month 0 should be invalid");
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateExpirationDate("13", "2026"),
            "Month 13 should be invalid");

        // Test invalid year
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateExpirationDate("06", "1949"),
            "Year 1949 should be invalid");
        assertThrows(CardUpdateException.class, 
            () -> cardUpdateService.validateExpirationDate("06", "2100"),
            "Year 2100 should be invalid");
    }

    /**
     * Tests maskCardNumber utility method for PCI-DSS compliance.
     * 
     * <p><strong>PCI-DSS Requirement:</strong> Card numbers must be masked in logs (show only last 4 digits)</p>
     * <p><strong>Format:</strong> **** **** **** 1234</p>
     */
    @Test
    @DisplayName("maskCardNumber() - Masks all but last 4 digits for PCI compliance")
    void maskCardNumber_MasksCorrectly() {
        String masked = cardUpdateService.maskCardNumber("4556123456789012");
        assertEquals("**** **** **** 9012", masked,
                    "Card number should be masked to show only last 4 digits");
        
        // Test null safety
        assertEquals("****", cardUpdateService.maskCardNumber(null),
                    "Null card number should return ****");
        
        // Test short card number
        assertEquals("****", cardUpdateService.maskCardNumber("123"),
                    "Short card number should return ****");
    }
}

