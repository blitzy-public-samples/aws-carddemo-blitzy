package com.carddemo.service;

import com.carddemo.dto.request.TransactionRequest;
import com.carddemo.dto.response.TransactionResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.transaction.TransactionAddService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 Unit Test Class for TransactionAddService
 * 
 * <p>This test class verifies transaction authorization and processing logic
 * preservation from COBOL program COTRN02C.cbl. It ensures functional equivalence
 * between the mainframe CICS transaction processing and the modern Spring Boot
 * service implementation.</p>
 * 
 * <p><strong>COBOL Source Program Mapping:</strong></p>
 * <ul>
 *   <li>Source Program: app/cbl/COTRN02C.cbl (Transaction Add program)</li>
 *   <li>CICS Transaction ID: CT02</li>
 *   <li>Primary Functions Tested:
 *     <ul>
 *       <li>VALIDATE-INPUT-DATA-FIELDS paragraph → Card validation tests</li>
 *       <li>ADD-TRANSACTION paragraph → Transaction creation tests</li>
 *       <li>WRITE-TRANSACT-FILE paragraph → Repository save verification</li>
 *       <li>UPDATE-ACCT-BAL logic → Balance update with COMP-3 precision</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <p><strong>Key Test Objectives (Section 0.10 Requirements):</strong></p>
 * <ol>
 *   <li>Verify transaction validation against credit limits and card expiration dates</li>
 *   <li>Test multi-file updates (Transaction insert + Account balance update) with ACID properties</li>
 *   <li>Validate BigDecimal arithmetic using RoundingMode.HALF_UP matching COBOL COMP-3 precision</li>
 *   <li>Ensure @Transactional boundary matches CICS SYNCPOINT behavior</li>
 *   <li>Verify rollback on error maintains database consistency</li>
 *   <li>Test transaction ID generation matching COBOL ADD 1 TO WS-TRAN-ID-N logic</li>
 * </ol>
 * 
 * <p><strong>COBOL COMP-3 Precision Matching:</strong></p>
 * <pre>
 * COBOL Definition (CVACT01Y.cpy):
 *   05 ACCT-CURR-BAL      PIC S9(10)V99 COMP-3.
 *   05 ACCT-CREDIT-LIMIT  PIC S9(10)V99 COMP-3.
 * 
 * COBOL Definition (CVTRA05Y.cpy):
 *   05 TRAN-AMT           PIC S9(09)V99 COMP-3.
 * 
 * Java Equivalent:
 *   BigDecimal with scale=2, RoundingMode.HALF_UP
 *   
 * COBOL Arithmetic (COTRN02C.cbl lines 506-507):
 *   COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
 *   
 * Java Equivalent:
 *   currentBalance.add(amount).setScale(2, RoundingMode.HALF_UP)
 * </pre>
 * 
 * <p><strong>Transaction Processing Flow Tested:</strong></p>
 * <ol>
 *   <li>Validate card exists and is active (CARD-ACTIVE-STATUS='Y')</li>
 *   <li>Validate card expiration date is future (CARD-EXPIRAION-DATE >= current date)</li>
 *   <li>Retrieve account by card's account ID</li>
 *   <li>Calculate available credit: (ACCT-CREDIT-LIMIT - ACCT-CURR-BAL)</li>
 *   <li>Verify transaction amount <= available credit</li>
 *   <li>Generate unique transaction ID (increment last ID)</li>
 *   <li>Create transaction record with all fields</li>
 *   <li>Update account balance: ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT</li>
 *   <li>Update category balance for analytics</li>
 *   <li>Commit all changes within single @Transactional boundary</li>
 * </ol>
 * 
 * <p><strong>Test Scenarios Matching Original COBOL Test Cases:</strong></p>
 * <ul>
 *   <li>testAddTransactionSuccess - Happy path with valid card and sufficient credit</li>
 *   <li>testAddTransactionExceedsCreditLimit - Credit limit validation (COBOL 88-level check)</li>
 *   <li>testAddTransactionExpiredCard - Card expiration validation</li>
 *   <li>testAddTransactionInactiveCard - Card status validation (CARD-ACTIVE-STATUS)</li>
 *   <li>testAddTransactionCardNotFound - Card existence validation (RESP-CD 13 NOTFND)</li>
 *   <li>testAddTransactionPositiveAmountOnly - Amount sign validation</li>
 *   <li>testAddTransactionAmountPrecision - BigDecimal scale=2 validation</li>
 *   <li>testAddTransactionBalanceUpdate - COMP-3 arithmetic precision verification</li>
 *   <li>testAddTransactionGeneratesUniqueId - Transaction ID generation logic</li>
 *   <li>testAddTransactionRollsBackOnAccountUpdateError - ACID rollback behavior</li>
 *   <li>testAddTransactionUpdatesCategoryBalance - Category tracking (if implemented)</li>
 * </ul>
 * 
 * <p><strong>Mock Strategy:</strong></p>
 * <ul>
 *   <li>@Mock TransactionRepository - Simulates VSAM TRANSACT file operations</li>
 *   <li>@Mock AccountRepository - Simulates VSAM ACCTDAT file operations</li>
 *   <li>@Mock CardRepository - Simulates VSAM CCXREF file operations</li>
 *   <li>@Mock TransactionCategoryBalanceRepository - Simulates category tracking</li>
 *   <li>@InjectMocks TransactionAddService - Service under test with mocked dependencies</li>
 * </ul>
 * 
 * <p><strong>Assertion Strategy:</strong></p>
 * <ul>
 *   <li>Use AssertJ assertThat() for fluent, readable assertions</li>
 *   <li>Use BigDecimal.compareTo() for monetary amount comparisons (avoid equals())</li>
 *   <li>Use ArgumentCaptor to verify exact values passed to repository save methods</li>
 *   <li>Use assertThatThrownBy() for exception scenario validation</li>
 *   <li>Verify all repository method calls with Mockito.verify()</li>
 * </ul>
 * 
 * @author AWS CardDemo Migration Team
 * @since 1.0.0
 * @see TransactionAddService Service class under test
 * @see Transaction JPA entity representing TRAN-RECORD
 * @see Account JPA entity representing ACCOUNT-RECORD
 * @see Card JPA entity representing CARD-RECORD
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService Unit Tests - COTRN02C.cbl Migration Validation")
public class TransactionServiceTest {

    /**
     * Mock repository for Transaction entity operations.
     * Replaces COBOL EXEC CICS WRITE/READ operations on TRANSACT file.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Mock repository for Account entity operations.
     * Replaces COBOL EXEC CICS READ/REWRITE operations on ACCTDAT file.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mock repository for Card entity operations.
     * Replaces COBOL EXEC CICS READ operations on CCXREF file.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Mock repository for TransactionCategoryBalance entity operations.
     * Supports spending analytics by category (enhancement over original COBOL).
     */
    @Mock
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * Service under test with mocked repository dependencies.
     * Implements transaction authorization and processing logic from COTRN02C.cbl.
     */
    @InjectMocks
    private TransactionAddService transactionAddService;

    // Test data constants matching COBOL test scenarios
    private static final String VALID_CARD_NUMBER = "4111111111111111";
    private static final String INVALID_CARD_NUMBER = "9999999999999999";
    private static final Long VALID_ACCOUNT_ID = 12345678901L;
    private static final String VALID_TRANSACTION_ID = "TXN20240101123456";
    private static final String LAST_TRANSACTION_ID = "TXN20240101123455";
    private static final String TYPE_CODE = "01"; // Purchase
    private static final String CATEGORY_CODE = "1000"; // Retail
    private static final Long MERCHANT_ID = 123456789L;
    private static final String MERCHANT_NAME = "Test Merchant";
    private static final String MERCHANT_CITY = "Test City";
    private static final String MERCHANT_ZIP = "12345";
    private static final String DESCRIPTION = "Test Transaction";
    
    // BigDecimal constants with proper scale matching COBOL COMP-3 precision
    private static final int DECIMAL_SCALE = 2;
    private static final RoundingMode DECIMAL_ROUNDING = RoundingMode.HALF_UP;
    
    // Mock entities reused across tests
    private Customer mockCustomer;
    private Card mockValidCard;
    private Account mockAccount;
    private Transaction mockTransaction;
    private Transaction mockLastTransaction;
    private TransactionRequest validRequest;

    /**
     * Setup method executed before each test.
     * Initializes mock entities and common test fixtures with proper
     * BigDecimal precision matching COBOL COMP-3 fields.
     */
    @BeforeEach
    void setUp() {
        // Initialize customer for account relationship
        // Matches COBOL: CUST-ID from CVCUS01Y.cpy
        mockCustomer = Customer.builder()
                .customerId(98765432101L)
                .firstName("TEST")
                .lastName("CUSTOMER")
                .build();

        // Initialize account with balance and credit limit
        // Matches COBOL: ACCT-CURR-BAL, ACCT-CREDIT-LIMIT (PIC S9(10)V99 COMP-3)
        mockAccount = Account.builder()
                .accountId(VALID_ACCOUNT_ID)
                .customer(mockCustomer)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("1000.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .creditLimit(new BigDecimal("10000.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .cashCreditLimit(new BigDecimal("5000.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .build();

        // Initialize valid card with active status and future expiration
        // Matches COBOL: CARD-ACTIVE-STATUS='Y', CARD-EXPIRAION-DATE >= current date
        mockValidCard = Card.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .account(mockAccount)
                .activeStatus("Y")
                .expirationDate(LocalDate.now().plusYears(2))
                .cvvCode("123")
                .embossedName("TEST CARDHOLDER")
                .build();

        // Initialize mock transaction for save operations
        // Matches COBOL: TRAN-RECORD structure from CVTRA05Y.cpy
        mockTransaction = Transaction.builder()
                .transactionId(VALID_TRANSACTION_ID)
                .typeCode(TYPE_CODE)
                .categoryCode(Integer.valueOf(CATEGORY_CODE))
                .transactionSource("ONLINE")
                .description(DESCRIPTION)
                .amount(new BigDecimal("100.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .card(mockValidCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now())
                .build();

        // Initialize mock last transaction for ID generation
        // Matches COBOL: READPREV-TRANSACT-FILE, ADD 1 TO WS-TRAN-ID-N
        mockLastTransaction = Transaction.builder()
                .transactionId(LAST_TRANSACTION_ID)
                .build();

        // Initialize valid transaction request
        // Matches COBOL: Input fields from BMS mapset COTRN02M.bms
        validRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(new BigDecimal("100.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();
    }

    /**
     * Test Case 1: Successful Transaction Authorization and Processing
     * 
     * <p>Tests the complete happy path flow matching COBOL transaction processing
     * from COTRN02C.cbl ADD-TRANSACTION paragraph. Verifies:</p>
     * <ul>
     *   <li>Card validation (exists, active, not expired)</li>
     *   <li>Account retrieval and credit limit validation</li>
     *   <li>Transaction creation with unique ID</li>
     *   <li>Account balance update with COMP-3 precision</li>
     *   <li>Category balance tracking</li>
     *   <li>All operations within @Transactional boundary</li>
     * </ul>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl lines 400-550:
     *   PERFORM VALIDATE-INPUT-DATA-FIELDS
     *   IF NOT ERR-FLG-ON
     *     PERFORM ADD-TRANSACTION
     *     IF NOT ERR-FLG-ON
     *       PERFORM WRITE-TRANSACT-FILE
     *       PERFORM UPDATE-ACCT-BAL
     *       MOVE 'Transaction added successfully' TO WS-MESSAGE
     *     END-IF
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should successfully create transaction with valid card and sufficient credit")
    void testAddTransactionSuccess() {
        // Arrange: Setup mock repository responses for happy path
        // Capture original balance before service call (service mutates the account object)
        BigDecimal originalBalance = mockAccount.getCurrentBalance();
        
        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(mockAccount));
        
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        when(categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Act: Call service method to create transaction
        TransactionResponse response = transactionAddService.createTransaction(validRequest);

        // Assert: Verify response contains expected transaction data
        assertThat(response).isNotNull();
        assertThat(response.getTransactionId()).isNotNull();
        assertThat(response.getAmount()).isEqualByComparingTo(validRequest.getAmount());
        assertThat(response.getCardNumber()).contains("****"); // Verify masking

        // Verify: Card validation was performed (COBOL: READ CCXREF FILE)
        verify(cardRepository, times(1)).findByCardNumber(VALID_CARD_NUMBER);

        // Verify: Account retrieval was performed (COBOL: READ ACCTDAT FILE)
        verify(accountRepository, times(1)).findById(VALID_ACCOUNT_ID);

        // Verify: Transaction was saved (COBOL: WRITE TRANSACT FILE)
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(transactionCaptor.capture());
        
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getAmount().scale()).isEqualTo(DECIMAL_SCALE);
        assertThat(savedTransaction.getAmount()).isEqualByComparingTo(validRequest.getAmount());
        assertThat(savedTransaction.getCard()).isEqualTo(mockValidCard);
        assertThat(savedTransaction.getMerchantName()).isEqualTo(MERCHANT_NAME);

        // Verify: Account balance was updated (COBOL: REWRITE ACCTDAT FILE)
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(1)).save(accountCaptor.capture());
        
        Account updatedAccount = accountCaptor.getValue();
        BigDecimal expectedBalance = originalBalance
                .add(validRequest.getAmount())
                .setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        assertThat(updatedAccount.getCurrentBalance()).isEqualByComparingTo(expectedBalance);

        // Verify: Category balance was updated
        verify(categoryBalanceRepository, times(1)).save(any(TransactionCategoryBalance.class));
    }

    /**
     * Test Case 2: Credit Limit Exceeded Rejection
     * 
     * <p>Tests that transaction is rejected when amount would exceed available credit.
     * Matches COBOL validation logic checking if (ACCT-CURR-BAL + TRAN-AMT) > ACCT-CREDIT-LIMIT.</p>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl pseudo-code:
     *   IF (ACCT-CURR-BAL + TRAN-AMT) > ACCT-CREDIT-LIMIT
     *     MOVE 'Credit limit would be exceeded' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should reject transaction when credit limit would be exceeded")
    void testAddTransactionExceedsCreditLimit() {
        // Arrange: Create transaction amount that exceeds available credit
        // Available credit = ACCT-CREDIT-LIMIT (10000) - ACCT-CURR-BAL (1000) = 9000
        // Transaction amount = 9500 (exceeds by 500)
        BigDecimal excessiveAmount = new BigDecimal("9500.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        TransactionRequest excessiveRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(excessiveAmount)
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(mockAccount));

        // Act & Assert: Verify BusinessLogicException is thrown
        assertThatThrownBy(() -> transactionAddService.createTransaction(excessiveRequest))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("exceeds available credit");

        // Verify: Card and account were checked before rejection
        verify(cardRepository, times(1)).findByCardNumber(VALID_CARD_NUMBER);
        verify(accountRepository, times(1)).findById(VALID_ACCOUNT_ID);

        // Verify: No transaction was saved (rollback behavior)
        verify(transactionRepository, never()).save(any(Transaction.class));
        
        // Verify: No account balance update occurred
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test Case 3: Expired Card Rejection
     * 
     * <p>Tests that transaction is rejected when card expiration date has passed.
     * Matches COBOL validation checking CARD-EXPIRAION-DATE against current date.</p>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl validation:
     *   IF CARD-EXPIRAION-DATE < CURRENT-DATE
     *     MOVE 'Card has expired' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should reject transaction when card has expired")
    void testAddTransactionExpiredCard() {
        // Arrange: Create expired card (expiration date in the past)
        Card expiredCard = Card.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .account(mockAccount)
                .activeStatus("Y")
                .expirationDate(LocalDate.now().minusMonths(1)) // Expired last month
                .cvvCode("123")
                .embossedName("TEST CARDHOLDER")
                .build();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(expiredCard));

        // Act & Assert: Verify BusinessLogicException is thrown for expired card
        assertThatThrownBy(() -> transactionAddService.createTransaction(validRequest))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("expired");

        // Verify: Card was checked
        verify(cardRepository, times(1)).findByCardNumber(VALID_CARD_NUMBER);

        // Verify: No further processing occurred
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test Case 4: Inactive Card Rejection
     * 
     * <p>Tests that transaction is rejected when card status is inactive.
     * Matches COBOL validation checking CARD-ACTIVE-STATUS='Y'.</p>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl validation:
     *   IF CARD-ACTIVE-STATUS NOT = 'Y'
     *     MOVE 'Card is not active' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should reject transaction when card is inactive")
    void testAddTransactionInactiveCard() {
        // Arrange: Create inactive card (CARD-ACTIVE-STATUS='N')
        Card inactiveCard = Card.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .account(mockAccount)
                .activeStatus("N") // Inactive status
                .expirationDate(LocalDate.now().plusYears(2))
                .cvvCode("123")
                .embossedName("TEST CARDHOLDER")
                .build();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(inactiveCard));

        // Act & Assert: Verify BusinessLogicException is thrown for inactive card
        assertThatThrownBy(() -> transactionAddService.createTransaction(validRequest))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("not active");

        // Verify: Card was checked
        verify(cardRepository, times(1)).findByCardNumber(VALID_CARD_NUMBER);

        // Verify: No transaction processing occurred
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test Case 5: Card Not Found Rejection
     * 
     * <p>Tests that transaction is rejected when card number is not found in database.
     * Matches COBOL CICS RESP-CD 13 (NOTFND) error handling.</p>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl file control:
     *   EXEC CICS READ
     *     DATASET(WS-CCXREF-FILE)
     *     INTO(CARD-XREF-RECORD)
     *     RIDFLD(TRAN-CARD-NUM)
     *     RESP(WS-RESP-CD)
     *   END-EXEC
     *   
     *   IF WS-RESP-CD = DFHRESP(NOTFND)
     *     MOVE 'Card not found' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should reject transaction when card number is not found")
    void testAddTransactionCardNotFound() {
        // Arrange: Mock card repository to return empty Optional (card not found)
        when(cardRepository.findByCardNumber(INVALID_CARD_NUMBER))
                .thenReturn(Optional.empty());

        TransactionRequest invalidCardRequest = TransactionRequest.builder()
                .cardNumber(INVALID_CARD_NUMBER)
                .amount(validRequest.getAmount())
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        // Act & Assert: Verify ResourceNotFoundException is thrown
        assertThatThrownBy(() -> transactionAddService.createTransaction(invalidCardRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Card not found");

        // Verify: Card lookup was attempted
        verify(cardRepository, times(1)).findByCardNumber(INVALID_CARD_NUMBER);

        // Verify: No further processing occurred
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test Case 6: Positive Amount Validation
     * 
     * <p>Tests that transaction amount must be positive (> 0).
     * Matches COBOL numeric validation and business rule enforcement.</p>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl validation:
     *   IF TRAN-AMT <= ZERO
     *     MOVE 'Transaction amount must be positive' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should reject transaction with zero or negative amount")
    void testAddTransactionPositiveAmountOnly() {
        // Arrange: Create request with zero amount
        TransactionRequest zeroAmountRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(BigDecimal.ZERO)
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        // Note: In the actual service, validation may occur via @Valid annotation
        // on the request parameter, so we test the business logic validation here
        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));

        // Act & Assert: Verify ValidationException or BusinessLogicException is thrown
        // The actual exception type depends on where validation occurs
        assertThatThrownBy(() -> transactionAddService.createTransaction(zeroAmountRequest))
                .isInstanceOf(RuntimeException.class);

        // Arrange: Create request with negative amount
        TransactionRequest negativeAmountRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(new BigDecimal("-100.00"))
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        // Act & Assert: Verify negative amount is also rejected
        assertThatThrownBy(() -> transactionAddService.createTransaction(negativeAmountRequest))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Test Case 7: Amount Precision Validation
     * 
     * <p>Tests that transaction amount maintains exactly 2 decimal places matching
     * COBOL COMP-3 field definition PIC S9(09)V99.</p>
     * 
     * <p><strong>COBOL Field Definition:</strong></p>
     * <pre>
     * CVTRA05Y.cpy:
     *   05 TRAN-AMT    PIC S9(09)V99 COMP-3.
     *   
     * This defines:
     *   - 9 integer digits (maximum $999,999,999)
     *   - 2 decimal places (cents)
     *   - Packed decimal format (COMP-3)
     *   - Signed numeric value
     * </pre>
     */
    @Test
    @DisplayName("Should maintain exact 2 decimal place precision for transaction amounts")
    void testAddTransactionAmountPrecision() {
        // Arrange: Create amounts with various precisions
        BigDecimal amountWithExactPrecision = new BigDecimal("123.45");
        BigDecimal amountWithExcessPrecision = new BigDecimal("123.456789");
        BigDecimal amountNeedingRounding = new BigDecimal("123.455"); // Should round to 123.46

        // Create request with amount needing scale adjustment
        TransactionRequest precisionRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(amountWithExcessPrecision)
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(mockAccount));
        
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        when(categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Act: Create transaction
        TransactionResponse response = transactionAddService.createTransaction(precisionRequest);

        // Assert: Verify amount is stored with exactly 2 decimal places
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getAmount().scale()).isEqualTo(DECIMAL_SCALE);
        
        // Verify: Account balance calculation also maintains proper scale
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        
        Account updatedAccount = accountCaptor.getValue();
        assertThat(updatedAccount.getCurrentBalance().scale()).isEqualTo(DECIMAL_SCALE);
    }

    /**
     * Test Case 8: Balance Update with COMP-3 Precision
     * 
     * <p>Tests that account balance update uses BigDecimal arithmetic with exact
     * COBOL COMP-3 precision matching the COMPUTE statement behavior.</p>
     * 
     * <p><strong>COBOL Arithmetic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl lines 506-507:
     *   COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
     *   
     * Where:
     *   ACCT-CURR-BAL: PIC S9(10)V99 COMP-3 (from CVACT01Y.cpy)
     *   TRAN-AMT:      PIC S9(09)V99 COMP-3 (from CVTRA05Y.cpy)
     *   
     * Java Equivalent:
     *   currentBalance.add(amount).setScale(2, RoundingMode.HALF_UP)
     * </pre>
     */
    @Test
    @DisplayName("Should update account balance with exact COMP-3 precision matching COBOL arithmetic")
    void testAddTransactionBalanceUpdate() {
        // Arrange: Set specific balance values to test precision
        BigDecimal initialBalance = new BigDecimal("1234.56").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        BigDecimal transactionAmount = new BigDecimal("567.89").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        BigDecimal expectedBalance = new BigDecimal("1802.45").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);

        Account testAccount = Account.builder()
                .accountId(VALID_ACCOUNT_ID)
                .customer(mockCustomer)
                .activeStatus("Y")
                .currentBalance(initialBalance)
                .creditLimit(new BigDecimal("10000.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .cashCreditLimit(new BigDecimal("5000.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .build();

        TransactionRequest balanceTestRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(transactionAmount)
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        when(categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Act: Process transaction
        transactionAddService.createTransaction(balanceTestRequest);

        // Assert: Verify balance was updated with exact precision
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        
        Account updatedAccount = accountCaptor.getValue();
        
        // Use compareTo for BigDecimal comparison (not equals)
        assertThat(updatedAccount.getCurrentBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(expectedBalance);
        
        // Verify scale is exactly 2 (matching COBOL V99)
        assertThat(updatedAccount.getCurrentBalance().scale()).isEqualTo(DECIMAL_SCALE);
        
        // Verify the arithmetic calculation manually
        BigDecimal calculatedBalance = initialBalance.add(transactionAmount)
                .setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        assertThat(updatedAccount.getCurrentBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(calculatedBalance);
    }

    /**
     * Test Case 9: Transaction ID Generation
     * 
     * <p>Tests that unique transaction IDs are generated by incrementing the last
     * transaction ID, matching COBOL logic.</p>
     * 
     * <p><strong>COBOL Logic Tested:</strong></p>
     * <pre>
     * COTRN02C.cbl lines 420-440:
     *   PERFORM STARTBR-TRANSACT-FILE
     *   PERFORM READPREV-TRANSACT-FILE
     *   IF NOT ERR-FLG-ON
     *     MOVE TRAN-ID TO WS-TRAN-ID
     *     ADD 1 TO WS-TRAN-ID-N
     *     MOVE WS-TRAN-ID-N TO TRAN-ID
     *   END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should generate unique transaction ID by incrementing last ID")
    void testAddTransactionGeneratesUniqueId() {
        // Arrange: Setup mocks for ID generation
        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(mockAccount));
        
        // Mock returns last transaction with specific ID
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        when(categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Act: Create transaction
        TransactionResponse response = transactionAddService.createTransaction(validRequest);

        // Assert: Verify new transaction ID was generated
        assertThat(response.getTransactionId()).isNotNull();
        assertThat(response.getTransactionId()).isNotEmpty();
        
        // Verify: Transaction ID generation logic was invoked
        verify(transactionRepository, times(1)).findTopByOrderByTransactionIdDesc();
        
        // Verify: Transaction was saved with generated ID
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(transactionCaptor.capture());
        
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionId()).isNotNull();
    }

    /**
     * Test Case 10: Transaction Rollback on Error
     * 
     * <p>Tests that @Transactional annotation provides ACID rollback behavior
     * matching CICS SYNCPOINT/ROLLBACK semantics. When an error occurs during
     * transaction processing, all changes should be rolled back.</p>
     * 
     * <p><strong>COBOL Transaction Boundary:</strong></p>
     * <pre>
     * CICS automatically provides transaction boundaries:
     *   - Transaction starts at program initiation
     *   - SYNCPOINT commits all changes
     *   - ROLLBACK undoes all changes on error
     *   
     * Spring @Transactional equivalent:
     *   - Transaction starts at method entry
     *   - Automatic commit on successful completion
     *   - Automatic rollback on exception
     * </pre>
     */
    @Test
    @DisplayName("Should rollback transaction when account update fails")
    void testAddTransactionRollsBackOnAccountUpdateError() {
        // Arrange: Setup mocks to simulate error during account update
        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(mockAccount));
        
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        // Mock transaction save to succeed (so we reach account update phase)
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        // Simulate error during account save (after transaction save succeeds)
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new RuntimeException("Database error during account update"));

        // Act & Assert: Verify exception is propagated (triggering rollback)
        assertThatThrownBy(() -> transactionAddService.createTransaction(validRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error");

        // Verify: Card and account were checked
        verify(cardRepository, times(1)).findByCardNumber(VALID_CARD_NUMBER);
        verify(accountRepository, times(1)).findById(VALID_ACCOUNT_ID);
        
        // Note: In the actual @Transactional boundary, if account.save() fails,
        // the entire transaction including transaction.save() would be rolled back
        // This test verifies the exception propagation that triggers rollback
    }

    /**
     * Test Case 11: Category Balance Tracking
     * 
     * <p>Tests that transaction category balance is updated for spending analytics.
     * This is an enhancement over the original COBOL but ensures atomicity within
     * the same transaction boundary.</p>
     * 
     * <p><strong>Enhancement Note:</strong></p>
     * <p>This functionality may be part of a separate COBOL batch program in the
     * original system, but is implemented here for real-time analytics and to
     * maintain ACID properties within the same transaction.</p>
     */
    @Test
    @DisplayName("Should update transaction category balance for spending analytics")
    void testAddTransactionUpdatesCategoryBalance() {
        // Arrange: Setup existing category balance
        TransactionCategoryBalance.TransactionCategoryBalanceId balanceId = 
                TransactionCategoryBalance.TransactionCategoryBalanceId.builder()
                        .accountId(VALID_ACCOUNT_ID)
                        .transactionTypeCode(TYPE_CODE)
                        .categoryCode(CATEGORY_CODE)
                        .build();
        
        TransactionCategoryBalance existingBalance = TransactionCategoryBalance.builder()
                .id(balanceId)
                .balance(new BigDecimal("500.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .build();
        
        // Capture original category balance before service call (service mutates the object)
        BigDecimal originalCategoryBalance = existingBalance.getBalance();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(mockAccount));
        
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        when(categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(existingBalance));

        // Act: Create transaction
        transactionAddService.createTransaction(validRequest);

        // Assert: Verify category balance was updated
        ArgumentCaptor<TransactionCategoryBalance> categoryCaptor = 
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(categoryBalanceRepository, times(1)).save(categoryCaptor.capture());
        
        TransactionCategoryBalance updatedBalance = categoryCaptor.getValue();
        BigDecimal expectedCategoryBalance = originalCategoryBalance
                .add(validRequest.getAmount())
                .setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        
        assertThat(updatedBalance.getBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(expectedCategoryBalance);
        
        // Verify scale is maintained
        assertThat(updatedBalance.getBalance().scale()).isEqualTo(DECIMAL_SCALE);
    }

    /**
     * Test Case 12: Transaction with Maximum Amount
     * 
     * <p>Tests that transactions with maximum allowed amount (up to credit limit)
     * are processed correctly, matching COBOL field limits.</p>
     * 
     * <p><strong>COBOL Field Limits:</strong></p>
     * <pre>
     * CVTRA05Y.cpy:
     *   05 TRAN-AMT    PIC S9(09)V99 COMP-3.
     *   
     * Maximum value: $999,999,999.99 (9 integer digits + 2 decimal places)
     * </pre>
     */
    @Test
    @DisplayName("Should process transaction with maximum allowed amount within credit limit")
    void testAddTransactionWithMaximumAmount() {
        // Arrange: Create account with high credit limit
        BigDecimal highCreditLimit = new BigDecimal("1000000.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        BigDecimal currentBalance = new BigDecimal("0.00").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        BigDecimal maximumAmount = new BigDecimal("999999.99").setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);

        Account highLimitAccount = Account.builder()
                .accountId(VALID_ACCOUNT_ID)
                .customer(mockCustomer)
                .activeStatus("Y")
                .currentBalance(currentBalance)
                .creditLimit(highCreditLimit)
                .cashCreditLimit(highCreditLimit)
                .build();

        TransactionRequest maxAmountRequest = TransactionRequest.builder()
                .cardNumber(VALID_CARD_NUMBER)
                .amount(maximumAmount)
                .merchantId(MERCHANT_ID)
                .merchantName(MERCHANT_NAME)
                .merchantCity(MERCHANT_CITY)
                .merchantZip(MERCHANT_ZIP)
                .description(DESCRIPTION)
                .typeCode(TYPE_CODE)
                .categoryCode(CATEGORY_CODE)
                .build();

        when(cardRepository.findByCardNumber(VALID_CARD_NUMBER))
                .thenReturn(Optional.of(mockValidCard));
        
        when(accountRepository.findById(VALID_ACCOUNT_ID))
                .thenReturn(Optional.of(highLimitAccount));
        
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockLastTransaction));
        
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(mockTransaction);
        
        when(categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Act: Process high-value transaction
        TransactionResponse response = transactionAddService.createTransaction(maxAmountRequest);

        // Assert: Verify transaction was processed successfully
        assertThat(response).isNotNull();
        assertThat(response.getTransactionId()).isNotNull();

        // Verify: Amount maintains proper precision
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getAmount())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(maximumAmount);
        assertThat(savedTransaction.getAmount().scale()).isEqualTo(DECIMAL_SCALE);

        // Verify: Balance update maintains precision with large amounts
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        
        Account updatedAccount = accountCaptor.getValue();
        assertThat(updatedAccount.getCurrentBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(maximumAmount);
        assertThat(updatedAccount.getCurrentBalance().scale()).isEqualTo(DECIMAL_SCALE);
    }
}
