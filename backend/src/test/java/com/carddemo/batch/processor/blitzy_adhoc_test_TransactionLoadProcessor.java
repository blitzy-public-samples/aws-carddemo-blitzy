package com.carddemo.batch.processor;

import com.carddemo.entity.Transaction;
import com.carddemo.entity.Card;
import com.carddemo.entity.Account;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.AccountRepository;
import com.carddemo.exception.ValidationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Ad-hoc unit tests for TransactionLoadProcessor validating COBOL-to-Java transformation.
 * 
 * Tests verify:
 * - Duplicate transaction detection (COBOL lines 170-184)
 * - Card number validation (COBOL lines 227-239)
 * - Account validation (COBOL lines 241-250)
 * - Business rule validations
 * - Error handling and logging
 */
@DisplayName("TransactionLoadProcessor Ad-hoc Tests")
public class blitzy_adhoc_test_TransactionLoadProcessor {
    
    private TransactionLoadProcessor processor;
    
    @Mock
    private TransactionRepository transactionRepository;
    
    @Mock
    private CardRepository cardRepository;
    
    @Mock
    private AccountRepository accountRepository;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new TransactionLoadProcessor(
            transactionRepository,
            cardRepository,
            accountRepository
        );
    }
    
    @Test
    @DisplayName("Test 1: Null transaction input should return null")
    void testNullTransactionInput() throws Exception {
        // Act
        Transaction result = processor.process(null);
        
        // Assert
        assertNull(result, "Null input should return null");
        verifyNoInteractions(transactionRepository, cardRepository, accountRepository);
    }
    
    @Test
    @DisplayName("Test 2: Duplicate transaction ID should return null")
    void testDuplicateTransactionDetection() throws Exception {
        // Arrange
        Transaction transaction = createValidTransaction();
        when(transactionRepository.existsById(transaction.getTransactionId())).thenReturn(true);
        
        // Act
        Transaction result = processor.process(transaction);
        
        // Assert
        assertNull(result, "Duplicate transaction should return null");
        verify(transactionRepository).existsById(transaction.getTransactionId());
        verifyNoInteractions(cardRepository, accountRepository);
    }
    
    @Test
    @DisplayName("Test 3: Invalid card number should throw ValidationException")
    void testInvalidCardNumberThrowsException() {
        // Arrange
        Transaction transaction = createValidTransaction();
        when(transactionRepository.existsById(anyString())).thenReturn(false);
        when(cardRepository.findByCardNumber(transaction.getCardNumber())).thenReturn(Optional.empty());
        
        // Act & Assert
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> processor.process(transaction),
            "Invalid card number should throw ValidationException"
        );
        
        assertTrue(exception.getMessage().contains("Invalid card number"), 
            "Exception message should indicate invalid card number");
        assertTrue(exception.getMessage().contains(transaction.getCardNumber()), 
            "Exception message should include the card number");
        verify(cardRepository).findByCardNumber(transaction.getCardNumber());
        verifyNoInteractions(accountRepository);
    }
    
    @Test
    @DisplayName("Test 4: Invalid account should throw ValidationException")
    void testInvalidAccountThrowsException() {
        // Arrange
        Transaction transaction = createValidTransaction();
        Card card = createValidCard();
        
        when(transactionRepository.existsById(anyString())).thenReturn(false);
        when(cardRepository.findByCardNumber(transaction.getCardNumber())).thenReturn(Optional.of(card));
        when(accountRepository.findById(card.getAccountId())).thenReturn(Optional.empty());
        
        // Act & Assert
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> processor.process(transaction),
            "Invalid account should throw ValidationException"
        );
        
        assertTrue(exception.getMessage().contains("not found"), 
            "Exception message should indicate account not found");
        assertTrue(exception.getMessage().contains("Account"), 
            "Exception message should mention Account");
        verify(accountRepository).findById(card.getAccountId());
    }
    
    @Test
    @DisplayName("Test 5: Negative transaction amount should throw ValidationException")
    void testNegativeAmountThrowsException() {
        // Arrange
        Transaction transaction = createValidTransaction();
        transaction.setTransactionAmount(new BigDecimal("-100.00"));
        
        Card card = createValidCard();
        Account account = createValidAccount();
        
        when(transactionRepository.existsById(anyString())).thenReturn(false);
        when(cardRepository.findByCardNumber(transaction.getCardNumber())).thenReturn(Optional.of(card));
        when(accountRepository.findById(card.getAccountId())).thenReturn(Optional.of(account));
        
        // Act & Assert
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> processor.process(transaction),
            "Negative amount should throw ValidationException"
        );
        
        assertTrue(exception.getMessage().contains("Transaction amount must be positive"), 
            "Exception message should indicate amount must be positive");
    }
    
    @Test
    @DisplayName("Test 6: Zero transaction amount should throw ValidationException")
    void testZeroAmountThrowsException() {
        // Arrange
        Transaction transaction = createValidTransaction();
        transaction.setTransactionAmount(BigDecimal.ZERO);
        
        Card card = createValidCard();
        Account account = createValidAccount();
        
        when(transactionRepository.existsById(anyString())).thenReturn(false);
        when(cardRepository.findByCardNumber(transaction.getCardNumber())).thenReturn(Optional.of(card));
        when(accountRepository.findById(card.getAccountId())).thenReturn(Optional.of(account));
        
        // Act & Assert
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> processor.process(transaction),
            "Zero amount should throw ValidationException"
        );
        
        assertTrue(exception.getMessage().contains("Transaction amount must be positive"), 
            "Exception message should indicate amount must be positive");
    }
    
    @Test
    @DisplayName("Test 7: Valid transaction should be processed successfully")
    void testValidTransactionProcessedSuccessfully() throws Exception {
        // Arrange
        Transaction transaction = createValidTransaction();
        Card card = createValidCard();
        Account account = createValidAccount();
        
        when(transactionRepository.existsById(anyString())).thenReturn(false);
        when(cardRepository.findByCardNumber(transaction.getCardNumber())).thenReturn(Optional.of(card));
        when(accountRepository.findById(card.getAccountId())).thenReturn(Optional.of(account));
        
        // Act
        Transaction result = processor.process(transaction);
        
        // Assert
        assertNotNull(result, "Valid transaction should be returned");
        assertEquals(transaction.getTransactionId(), result.getTransactionId());
        assertEquals(transaction.getTransactionAmount(), result.getTransactionAmount());
        
        verify(transactionRepository).existsById(transaction.getTransactionId());
        verify(cardRepository).findByCardNumber(transaction.getCardNumber());
        verify(accountRepository).findById(card.getAccountId());
    }
    
    @Test
    @DisplayName("Test 8: Missing origination timestamp should set default")
    void testMissingOriginationTimestampSetsDefault() throws Exception {
        // Arrange
        Transaction transaction = createValidTransaction();
        transaction.setOriginationTimestamp(null);
        
        Card card = createValidCard();
        Account account = createValidAccount();
        
        when(transactionRepository.existsById(anyString())).thenReturn(false);
        when(cardRepository.findByCardNumber(transaction.getCardNumber())).thenReturn(Optional.of(card));
        when(accountRepository.findById(card.getAccountId())).thenReturn(Optional.of(account));
        
        // Act
        Transaction result = processor.process(transaction);
        
        // Assert
        assertNotNull(result, "Transaction should be processed");
        assertNotNull(result.getOriginationTimestamp(), "Origination timestamp should be set");
    }
    
    // =========================================================================
    // Helper Methods to Create Test Data
    // =========================================================================
    
    private Transaction createValidTransaction() {
        Transaction transaction = new Transaction();
        transaction.setTransactionId("TRX1234567890123");
        transaction.setCardNumber("4111111111111111");
        transaction.setTransactionAmount(new BigDecimal("150.00"));
        transaction.setTransactionDescription("Test Transaction");
        transaction.setTransactionTypeCode("PUR");  // Purchase transaction type
        transaction.setTransactionSource("BATCH");
        transaction.setOriginationTimestamp(LocalDateTime.now());
        transaction.setProcessingTimestamp(LocalDateTime.now());
        return transaction;
    }
    
    private Card createValidCard() {
        Card card = new Card();
        card.setCardNumber("4111111111111111");
        card.setAccountId(12345678901L);
        card.setActiveStatus("Y");
        card.setEmbossedName("JOHN DOE");
        return card;
    }
    
    private Account createValidAccount() {
        Account account = new Account();
        account.setId(12345678901L);
        account.setActiveStatus("A");  // 'A' for Active per Account entity validation
        account.setCurrentBalance(new BigDecimal("5000.00"));
        account.setCreditLimit(new BigDecimal("10000.00"));
        return account;
    }
}
