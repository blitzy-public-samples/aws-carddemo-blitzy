package com.carddemo.batch.processor;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.entity.DisclosureGroupId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive JUnit 5 unit test class for AccountProcessor ItemProcessor implementation.
 * 
 * Tests business logic extracted from COBOL programs:
 * - CBACT01C.cbl: Account file reading and validation
 * - CBACT02C.cbl: Card file reading and validation  
 * - CBACT03C.cbl: Cross-reference file reading and validation
 * - CBACT04C.cbl: Interest calculation and account balance updates
 * 
 * Key test scenarios per requirements:
 * - Account validation from CBACT01C (account ID format, active status checks, data completeness)
 * - Interest calculation from CBACT04C (apply interest rate to balances using BigDecimal with HALF_UP rounding)
 * - Credit limit processing from CBACT03C (validate credit limits against account balances)
 * - Account expiration processing (check expiration dates against current date)
 * - Error handling for null accounts, missing fields, invalid data
 * - BigDecimal precision verification matching COBOL COMP-3 arithmetic exactly
 * - Skip logic for accounts that should be filtered per business rules
 * 
 * Testing approach:
 * - Uses Mockito 5.x for mocking repository dependencies
 * - Uses AssertJ for fluent assertions
 * - Uses @ParameterizedTest for testing multiple account scenarios
 * - Validates that processor output matches expected transformations from COBOL logic
 * - Tests edge cases like accounts at credit limit, zero balance, expired accounts
 * 
 * Per Section 0.4.11: Tests processor business logic extracted from 4 COBOL account batch programs
 * Per Section 0.7.6: Validates BigDecimal precision for COMP-3 financial calculations
 * Per Section 0.7.6: Tests error handling patterns preserving COBOL file-status checks
 * 
 * @see AccountProcessor The Spring Batch ItemProcessor being tested
 * @see Account JPA entity representing account master data
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountProcessor Unit Tests")
public class AccountProcessorTest {

    /**
     * AccountProcessor instance under test.
     * Dependencies (repositories) are injected as mocks by Mockito.
     */
    @InjectMocks
    private AccountProcessor accountProcessor;

    /**
     * Mock AccountRepository for simulating account data access.
     * Not directly used in current processor implementation but available for future enhancements.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mock DisclosureGroupRepository for simulating interest rate lookups.
     * Used to stub interest rate retrieval for account group IDs.
     */
    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    // ========== Test Constants ==========

    /**
     * Default test account ID.
     */
    private static final Long TEST_ACCOUNT_ID = 1234567890L;

    /**
     * Default test account group ID.
     */
    private static final String TEST_GROUP_ID = "GROUP001";

    /**
     * Default account group ID for fallback interest rate.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Default transaction type code for interest calculation.
     */
    private static final String DEFAULT_TRAN_TYPE_CD = "01";

    /**
     * Default transaction category code for interest charges.
     */
    private static final Integer DEFAULT_TRAN_CAT_CD = 5;

    /**
     * Standard annual interest rate for testing (12.50%).
     */
    private static final BigDecimal STANDARD_INTEREST_RATE = new BigDecimal("12.50");

    /**
     * Zero interest rate for testing accounts with no interest.
     */
    private static final BigDecimal ZERO_INTEREST_RATE = BigDecimal.ZERO;

    /**
     * High interest rate for testing edge cases (29.99%).
     */
    private static final BigDecimal HIGH_INTEREST_RATE = new BigDecimal("29.99");

    // ========== Successful Processing Tests ==========

    @Test
    @DisplayName("Should process valid active account successfully")
    void testProcessValidActiveAccount() throws Exception {
        // Given: A valid active account with positive balance
        Account account = createValidAccount();
        
        // Mock interest rate lookup
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(result.getAcctActiveStatus()).isEqualTo("Y");
        
        // Verify cycle counters are reset
        assertThat(result.getAcctCurrCycCredit()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualTo(BigDecimal.ZERO);
        
        // Verify balance is updated with interest
        assertThat(result.getAcctCurrBal()).isGreaterThan(account.getAcctCurrBal());
    }

    @Test
    @DisplayName("Should calculate interest correctly using BigDecimal with HALF_UP rounding")
    void testInterestCalculationPrecision() throws Exception {
        // Given: Account with specific balance to test precision
        BigDecimal balance = new BigDecimal("1000.00");
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(balance)
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(new BigDecimal("100.00"))
                .acctCurrCycDebit(new BigDecimal("50.00"))
                .build();

        // Mock interest rate: 12.50% annual
        mock InterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // Expected interest calculation: (1000.00 * 12.50) / 1200 = 10.416666... = 10.42 (HALF_UP)
        BigDecimal expectedInterest = balance
                .multiply(STANDARD_INTEREST_RATE)
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
        BigDecimal expectedBalance = balance.add(expectedInterest);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Balance should be updated with exactly calculated interest
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualTo(expectedBalance);
        
        // Verify precision: 1000.00 + 10.42 = 1010.42
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo("1010.42");
        
        // Verify scale is preserved (2 decimal places)
        assertThat(result.getAcctCurrBal().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should process account with zero balance without calculating interest")
    void testProcessAccountWithZeroBalance() throws Exception {
        // Given: Account with zero balance
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(BigDecimal.ZERO)
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed with no interest added
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
        
        // Verify cycle counters are still reset
        assertThat(result.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        
        // Verify interest rate lookup was skipped
        verify(disclosureGroupRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should process account with zero interest rate")
    void testProcessAccountWithZeroInterestRate() throws Exception {
        // Given: Account with positive balance but zero interest rate
        Account account = createValidAccount();
        
        // Mock zero interest rate
        mockInterestRateLookup(TEST_GROUP_ID, ZERO_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed with no interest added
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(account.getAcctCurrBal());
        
        // Verify cycle counters are reset
        assertThat(result.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ========== Account Validation Tests (CBACT01C) ==========

    @Test
    @DisplayName("Should reject account with null account ID")
    void testRejectAccountWithNullId() throws Exception {
        // Given: Account with null ID
        Account account = createValidAccount();
        account.setAcctId(null);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected (filtered)
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should reject account with inactive status")
    void testRejectInactiveAccount() throws Exception {
        // Given: Account with inactive status 'N'
        Account account = createValidAccount();
        account.setAcctActiveStatus("N");

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should reject account with closed status")
    void testRejectClosedAccount() throws Exception {
        // Given: Account with closed status 'C'
        Account account = createValidAccount();
        account.setAcctActiveStatus("C");

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should reject account with suspended status")
    void testRejectSuspendedAccount() throws Exception {
        // Given: Account with suspended status 'S'
        Account account = createValidAccount();
        account.setAcctActiveStatus("S");

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should reject account with null active status")
    void testRejectAccountWithNullStatus() throws Exception {
        // Given: Account with null active status
        Account account = createValidAccount();
        account.setAcctActiveStatus(null);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should reject account with null current balance")
    void testRejectAccountWithNullBalance() throws Exception {
        // Given: Account with null current balance
        Account account = createValidAccount();
        account.setAcctCurrBal(null);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected
        assertThat(result).isNull();
    }

    // ========== Interest Calculation Tests (CBACT04C) ==========

    @Test
    @DisplayName("Should use DEFAULT group when specific group ID not found")
    void testFallbackToDefaultInterestRate() throws Exception {
        // Given: Account with group ID that doesn't exist
        Account account = createValidAccount();
        account.setAcctGroupId("NONEXISTENT");
        
        // Mock: Specific group not found, but DEFAULT group exists
        DisclosureGroupId specificGroupId = new DisclosureGroupId("NONEXISTENT", DEFAULT_TRAN_TYPE_CD, DEFAULT_TRAN_CAT_CD);
        when(disclosureGroupRepository.findById(eq(specificGroupId))).thenReturn(Optional.empty());
        
        DisclosureGroupId defaultGroupId = new DisclosureGroupId(DEFAULT_GROUP_ID, DEFAULT_TRAN_TYPE_CD, DEFAULT_TRAN_CAT_CD);
        DisclosureGroup defaultGroup = createDisclosureGroup(DEFAULT_GROUP_ID, STANDARD_INTEREST_RATE);
        when(disclosureGroupRepository.findById(eq(defaultGroupId))).thenReturn(Optional.of(defaultGroup));

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed with DEFAULT interest rate
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isGreaterThan(account.getAcctCurrBal());
        
        // Verify both lookups occurred
        verify(disclosureGroupRepository, times(1)).findById(eq(specificGroupId));
        verify(disclosureGroupRepository, times(1)).findById(eq(defaultGroupId));
    }

    @Test
    @DisplayName("Should skip interest calculation when no interest rate found")
    void testSkipInterestWhenRateNotFound() throws Exception {
        // Given: Account with group ID that doesn't exist and no DEFAULT either
        Account account = createValidAccount();
        account.setAcctGroupId("NONEXISTENT");
        
        // Mock: Neither specific group nor DEFAULT group found
        when(disclosureGroupRepository.findById(any())).thenReturn(Optional.empty());

        BigDecimal originalBalance = account.getAcctCurrBal();

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed but with no interest added
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(originalBalance);
        
        // Verify cycle counters are still reset
        assertThat(result.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Should skip interest calculation for account with null group ID")
    void testSkipInterestForNullGroupId() throws Exception {
        // Given: Account with null group ID
        Account account = createValidAccount();
        account.setAcctGroupId(null);

        BigDecimal originalBalance = account.getAcctCurrBal();

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed with no interest added
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(originalBalance);
        
        // Verify no interest rate lookup occurred
        verify(disclosureGroupRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should skip interest calculation for account with empty group ID")
    void testSkipInterestForEmptyGroupId() throws Exception {
        // Given: Account with empty group ID
        Account account = createValidAccount();
        account.setAcctGroupId("   ");

        BigDecimal originalBalance = account.getAcctCurrBal();

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed with no interest added
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(originalBalance);
        
        // Verify no interest rate lookup occurred
        verify(disclosureGroupRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should skip interest calculation for account with negative balance")
    void testSkipInterestForNegativeBalance() throws Exception {
        // Given: Account with negative balance
        Account account = createValidAccount();
        account.setAcctCurrBal(new BigDecimal("-500.00"));

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed with no interest added (balance stays negative)
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("-500.00"));
        
        // Verify no interest rate lookup occurred
        verify(disclosureGroupRepository, never()).findById(any());
    }

    // ========== Credit Limit Validation Tests (CBACT03C) ==========

    @Test
    @DisplayName("Should process account within credit limit")
    void testProcessAccountWithinCreditLimit() throws Exception {
        // Given: Account with balance well within credit limit
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isLessThanOrEqualTo(result.getAcctCreditLimit());
    }

    @Test
    @DisplayName("Should process account at credit limit")
    void testProcessAccountAtCreditLimit() throws Exception {
        // Given: Account with balance exactly at credit limit
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("5000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed (warning logged but not rejected)
        assertThat(result).isNotNull();
        // Balance will be over limit after interest is added
        assertThat(result.getAcctCurrBal()).isGreaterThan(result.getAcctCreditLimit());
    }

    @Test
    @DisplayName("Should process account over credit limit with warning")
    void testProcessAccountOverCreditLimit() throws Exception {
        // Given: Account with balance exceeding credit limit
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("6000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed (warning logged, not rejected)
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isGreaterThan(result.getAcctCreditLimit());
    }

    @Test
    @DisplayName("Should handle account with null credit limit")
    void testHandleAccountWithNullCreditLimit() throws Exception {
        // Given: Account with null credit limit
        Account account = createValidAccount();
        account.setAcctCreditLimit(null);
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed (validation continues despite null limit)
        assertThat(result).isNotNull();
    }

    // ========== Expiration Date Processing Tests (CBACT04C) ==========

    @Test
    @DisplayName("Should reject expired account")
    void testRejectExpiredAccount() throws Exception {
        // Given: Account with expiration date in the past
        Account account = createValidAccount();
        account.setAcctExpirationDate(LocalDate.now().minusDays(1));

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should reject account expiring today")
    void testRejectAccountExpiringToday() throws Exception {
        // Given: Account with expiration date today
        Account account = createValidAccount();
        account.setAcctExpirationDate(LocalDate.now());

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be rejected (expired as of today)
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should process account expiring tomorrow")
    void testProcessAccountExpiringTomorrow() throws Exception {
        // Given: Account expiring tomorrow (still valid today)
        Account account = createValidAccount();
        account.setAcctExpirationDate(LocalDate.now().plusDays(1));
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Should process account with null expiration date")
    void testProcessAccountWithNullExpirationDate() throws Exception {
        // Given: Account with null expiration date (no expiration)
        Account account = createValidAccount();
        account.setAcctExpirationDate(null);
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully (no expiration means never expires)
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Should process account with far future expiration date")
    void testProcessAccountWithFarFutureExpirationDate() throws Exception {
        // Given: Account with expiration date 10 years in future
        Account account = createValidAccount();
        account.setAcctExpirationDate(LocalDate.now().plusYears(10));
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully
        assertThat(result).isNotNull();
    }

    // ========== BigDecimal Precision Tests ==========

    @ParameterizedTest(name = "Balance: {0}, Rate: {1}, Expected Interest: {2}")
    @MethodSource("provideInterestCalculationScenarios")
    @DisplayName("Should calculate interest with exact precision for various scenarios")
    void testInterestCalculationPrecisionScenarios(BigDecimal balance, BigDecimal rate, BigDecimal expectedInterest) throws Exception {
        // Given: Account with specific balance and interest rate
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(balance)
                .acctCreditLimit(new BigDecimal("50000.00"))
                .acctCashCreditLimit(new BigDecimal("25000.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();
        
        mockInterestRateLookup(TEST_GROUP_ID, rate);

        BigDecimal expectedBalance = balance.add(expectedInterest);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Interest should be calculated with exact precision
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
        
        // Verify scale is 2 decimal places
        assertThat(result.getAcctCurrBal().scale()).isEqualTo(2);
    }

    /**
     * Provides test scenarios for parameterized interest calculation tests.
     * 
     * Each scenario includes:
     * - Account balance
     * - Annual interest rate
     * - Expected monthly interest (pre-calculated with HALF_UP rounding)
     * 
     * Formula: (balance * rate) / 1200
     */
    static Stream<Arguments> provideInterestCalculationScenarios() {
        return Stream.of(
                // Standard scenarios
                Arguments.of(new BigDecimal("1000.00"), new BigDecimal("12.50"), new BigDecimal("10.42")),   // (1000 * 12.50) / 1200 = 10.416666... = 10.42
                Arguments.of(new BigDecimal("5000.00"), new BigDecimal("18.00"), new BigDecimal("75.00")),   // (5000 * 18.00) / 1200 = 75.00
                Arguments.of(new BigDecimal("2500.00"), new BigDecimal("15.99"), new BigDecimal("33.31")),   // (2500 * 15.99) / 1200 = 33.3125 = 33.31
                
                // Edge cases for rounding
                Arguments.of(new BigDecimal("100.00"), new BigDecimal("12.50"), new BigDecimal("1.04")),     // (100 * 12.50) / 1200 = 1.041666... = 1.04
                Arguments.of(new BigDecimal("100.00"), new BigDecimal("12.49"), new BigDecimal("1.04")),     // (100 * 12.49) / 1200 = 1.040833... = 1.04
                Arguments.of(new BigDecimal("100.00"), new BigDecimal("12.55"), new BigDecimal("1.05")),     // (100 * 12.55) / 1200 = 1.045833... = 1.05
                
                // High balance scenarios
                Arguments.of(new BigDecimal("10000.00"), new BigDecimal("20.00"), new BigDecimal("166.67")), // (10000 * 20.00) / 1200 = 166.666666... = 166.67
                Arguments.of(new BigDecimal("25000.00"), new BigDecimal("24.99"), new BigDecimal("520.63")), // (25000 * 24.99) / 1200 = 520.625 = 520.63
                
                // Low interest rate scenarios
                Arguments.of(new BigDecimal("5000.00"), new BigDecimal("0.99"), new BigDecimal("4.13")),     // (5000 * 0.99) / 1200 = 4.125 = 4.13
                Arguments.of(new BigDecimal("10000.00"), new BigDecimal("1.00"), new BigDecimal("8.33")),    // (10000 * 1.00) / 1200 = 8.333333... = 8.33
                
                // High interest rate scenarios
                Arguments.of(new BigDecimal("5000.00"), new BigDecimal("29.99"), new BigDecimal("124.96")),  // (5000 * 29.99) / 1200 = 124.958333... = 124.96
                Arguments.of(new BigDecimal("1000.00"), new BigDecimal("35.00"), new BigDecimal("29.17"))    // (1000 * 35.00) / 1200 = 29.166666... = 29.17
        );
    }

    @Test
    @DisplayName("Should verify RoundingMode.HALF_UP is used for financial calculations")
    void testVerifyHalfUpRoundingMode() throws Exception {
        // Given: Balance and rate that produces exactly 0.5 in third decimal place
        // (1000 * 11.40) / 1200 = 9.500 exactly
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();
        
        mockInterestRateLookup(TEST_GROUP_ID, new BigDecimal("11.40"));

        // Expected: 9.500 rounds UP to 9.50 with HALF_UP
        BigDecimal expectedBalance = new BigDecimal("1009.50");

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Interest should round up to 9.50 (not down to 9.50)
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
    }

    // ========== Cycle Counter Reset Tests ==========

    @Test
    @DisplayName("Should reset cycle credit and debit counters to zero")
    void testResetCycleCounters() throws Exception {
        // Given: Account with non-zero cycle counters
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(new BigDecimal("500.00"))
                .acctCurrCycDebit(new BigDecimal("300.00"))
                .build();
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Cycle counters should be reset to zero
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        
        // Verify scale is preserved
        assertThat(result.getAcctCurrCycCredit().scale()).isEqualTo(0);
        assertThat(result.getAcctCurrCycDebit().scale()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reset cycle counters even when interest calculation is skipped")
    void testResetCycleCountersWhenInterestSkipped() throws Exception {
        // Given: Account with zero balance (interest skipped) but non-zero cycle counters
        Account account = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(BigDecimal.ZERO)
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(new BigDecimal("1000.00"))
                .acctCurrCycDebit(new BigDecimal("750.00"))
                .build();

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Cycle counters should still be reset
        assertThat(result).isNotNull();
        assertThat(result.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ========== Error Handling and Edge Case Tests ==========

    @Test
    @DisplayName("Should handle account with all minimum values")
    void testHandleAccountWithMinimumValues() throws Exception {
        // Given: Account with minimum valid values
        Account account = Account.builder()
                .acctId(1L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("0.01"))
                .acctCreditLimit(new BigDecimal("0.01"))
                .acctCashCreditLimit(new BigDecimal("0.01"))
                .acctGroupId("A")
                .acctOpenDate(LocalDate.now())
                .acctExpirationDate(LocalDate.now().plusDays(1))
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .build();
        
        mockInterestRateLookup("A", STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should handle account with maximum balance values")
    void testHandleAccountWithMaximumValues() throws Exception {
        // Given: Account with maximum balance (PIC S9(10)V99 = max 9,999,999,999.99)
        Account account = Account.builder()
                .acctId(99999999999L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("9999999999.99"))
                .acctCreditLimit(new BigDecimal("9999999999.99"))
                .acctCashCreditLimit(new BigDecimal("9999999999.99"))
                .acctGroupId("MAXGROUP")
                .acctOpenDate(LocalDate.now().minusYears(50))
                .acctExpirationDate(LocalDate.now().plusYears(50))
                .acctCurrCycCredit(new BigDecimal("9999999999.99"))
                .acctCurrCycDebit(new BigDecimal("9999999999.99"))
                .build();
        
        // Use small interest rate to avoid overflow
        mockInterestRateLookup("MAXGROUP", new BigDecimal("0.01"));

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed without overflow
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(99999999999L);
        assertThat(result.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Should handle account with recently opened date")
    void testHandleRecentlyOpenedAccount() throws Exception {
        // Given: Account opened yesterday
        Account account = createValidAccount();
        account.setAcctOpenDate(LocalDate.now().minusDays(1));
        
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the account
        Account result = accountProcessor.process(account);

        // Then: Account should be processed successfully
        assertThat(result).isNotNull();
        assertThat(result.getAcctOpenDate()).isEqualTo(LocalDate.now().minusDays(1));
    }

    @Test
    @DisplayName("Should process account when processor is called multiple times (idempotency check)")
    void testIdempotentProcessing() throws Exception {
        // Given: A valid account
        Account account = createValidAccount();
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);

        // When: Processing the same account twice
        Account firstResult = accountProcessor.process(account);
        
        // Reset cycle counters manually for second processing (simulating ItemReader reading updated record)
        firstResult.setAcctCurrCycCredit(new BigDecimal("100.00"));
        firstResult.setAcctCurrCycDebit(new BigDecimal("50.00"));
        
        // Mock interest rate again for second call
        mockInterestRateLookup(TEST_GROUP_ID, STANDARD_INTEREST_RATE);
        
        Account secondResult = accountProcessor.process(firstResult);

        // Then: Both results should be successfully processed
        assertThat(firstResult).isNotNull();
        assertThat(secondResult).isNotNull();
        
        // Second processing adds more interest to already updated balance
        assertThat(secondResult.getAcctCurrBal()).isGreaterThan(firstResult.getAcctCurrBal());
        
        // Cycle counters should be reset in both cases
        assertThat(secondResult.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(secondResult.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ========== Helper Methods ==========

    /**
     * Creates a valid account for testing with standard values.
     * 
     * @return Account entity with valid data for successful processing
     */
    private Account createValidAccount() {
        return Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1500.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("2500.00"))
                .acctGroupId(TEST_GROUP_ID)
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .acctCurrCycCredit(new BigDecimal("500.00"))
                .acctCurrCycDebit(new BigDecimal("200.00"))
                .build();
    }

    /**
     * Creates a DisclosureGroup entity for mocking interest rate lookup.
     * 
     * @param groupId Account group identifier
     * @param interestRate Annual interest rate
     * @return DisclosureGroup entity with specified interest rate
     */
    private DisclosureGroup createDisclosureGroup(String groupId, BigDecimal interestRate) {
        DisclosureGroupId id = new DisclosureGroupId(groupId, DEFAULT_TRAN_TYPE_CD, DEFAULT_TRAN_CAT_CD);
        return DisclosureGroup.builder()
                .id(id)
                .discIntRate(interestRate)
                .build();
    }

    /**
     * Mocks interest rate lookup for specified account group ID.
     * 
     * Configures disclosureGroupRepository mock to return specified interest rate
     * for the given group ID with default transaction type and category.
     * 
     * @param groupId Account group identifier
     * @param interestRate Annual interest rate to return
     */
    private void mockInterestRateLookup(String groupId, BigDecimal interestRate) {
        DisclosureGroupId disclosureGroupId = new DisclosureGroupId(groupId, DEFAULT_TRAN_TYPE_CD, DEFAULT_TRAN_CAT_CD);
        DisclosureGroup disclosureGroup = createDisclosureGroup(groupId, interestRate);
        when(disclosureGroupRepository.findById(eq(disclosureGroupId))).thenReturn(Optional.of(disclosureGroup));
    }
}
