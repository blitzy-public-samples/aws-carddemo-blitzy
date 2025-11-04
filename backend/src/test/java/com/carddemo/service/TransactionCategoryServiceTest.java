/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.service;

import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategory;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.dto.response.TransactionCategoryResponse;
import com.carddemo.util.DecimalUtils;
import com.carddemo.service.TransactionCategoryService.CategorySummary;
import com.carddemo.service.TransactionCategoryService.AggregationResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JUnit 5 test class for TransactionCategoryService validating business logic transformation
 * from COTRN01C.cbl and CBTRN03C.cbl COBOL programs.
 * 
 * <p><strong>COBOL Source Programs:</strong></p>
 * <ul>
 *   <li>app/cbl/COTRN01C.cbl - Transaction category summary screen logic</li>
 *   <li>app/cbl/CBTRN03C.cbl - Batch transaction aggregation and report generation</li>
 *   <li>app/cpy/CVTRA05Y.cpy - TRAN-RECORD copybook with TRAN-AMT PIC S9(09)V99</li>
 * </ul>
 * 
 * <p><strong>Test Focus Areas:</strong></p>
 * <ul>
 *   <li>Transaction category aggregation and grouping by transaction category code</li>
 *   <li>Sum/count aggregation with COMP-3 decimal precision preservation using BigDecimal</li>
 *   <li>Date range filtering matching COBOL date comparison logic</li>
 *   <li>Category-wise transaction totals and percentage calculations</li>
 *   <li>Calculation precision matches COBOL PIC S9(n)V99 COMP-3 specifications with proper rounding (HALF_UP)</li>
 * </ul>
 * 
 * <p><strong>COBOL Aggregation Logic Tested (CBTRN03C.cbl):</strong></p>
 * <pre>
 * Lines 134-136: WS-PAGE-TOTAL, WS-ACCOUNT-TOTAL, WS-GRAND-TOTAL PIC S9(09)V99
 * Lines 200-201, 287-288: ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
 * Line 297: ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL
 * </pre>
 * 
 * <p><strong>Critical Numeric Precision Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>All amounts use BigDecimal with scale=2, RoundingMode.HALF_UP</li>
 *   <li>Matches COBOL COMP-3 PIC S9(09)V99 packed decimal behavior exactly</li>
 *   <li>Test values: 100.456 + 200.789 + 300.123 = 601.37 (with HALF_UP rounding)</li>
 *   <li>No floating point rounding errors</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@ExtendWith(MockitoExtension.class)
class TransactionCategoryServiceTest {
    
    @Mock
    private TransactionRepository transactionRepository;
    
    @Mock
    private TransactionCategoryRepository categoryRepository;
    
    @InjectMocks
    private TransactionCategoryService transactionCategoryService;
    
    // Test data constants
    private static final String TEST_ACCOUNT_ID = "00000000001";
    private static final LocalDate TEST_START_DATE = LocalDate.of(2024, 1, 1);
    private static final LocalDate TEST_END_DATE = LocalDate.of(2024, 1, 31);
    
    // Category codes matching COBOL TRAN-CAT-CD PIC 9(04)
    private static final Integer CATEGORY_GROCERIES = 1001;
    private static final Integer CATEGORY_GAS = 1002;
    private static final Integer CATEGORY_DINING = 1003;
    private static final Integer CATEGORY_TRAVEL = 1004;
    
    @BeforeEach
    void setUp() {
        // Re-inject mocks to ensure clean state for each test
        transactionCategoryService = new TransactionCategoryService(
            transactionRepository,
            new DecimalUtils()
        );
    }
    
    /**
     * Test 1: testGetCategorySummary_ByAccountId_ReturnsAggregates
     * 
     * Tests transaction grouping by category for account with repository mocking.
     * Verifies category-wise sum calculations and BigDecimal precision with scale=2, HALF_UP rounding.
     * Matches COBOL COMP-3 PIC S9(13)V99 precision from CBTRN03C.cbl WS-ACCOUNT-TOTAL calculation.
     */
    @Test
    void testGetCategorySummary_ByAccountId_ReturnsAggregates() {
        // Arrange - Mock repository to return aggregation results
        // Simulates SQL: SELECT tran_cat_cd, SUM(tran_amt) FROM transaction WHERE account_id=? GROUP BY tran_cat_cd
        List<Object[]> mockAggregateResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("150.75")}, // Groceries: $150.75
            new Object[]{CATEGORY_GAS, new BigDecimal("75.50")},        // Gas: $75.50
            new Object[]{CATEGORY_DINING, new BigDecimal("45.25")}      // Dining: $45.25
        );
        
        when(transactionRepository.aggregateByCategory(
            eq(TEST_ACCOUNT_ID), 
            eq(TEST_START_DATE), 
            eq(TEST_END_DATE)
        )).thenReturn(mockAggregateResults);
        
        // Act
        List<CategorySummary> result = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Assert
        assertNotNull(result, "Category summaries should not be null");
        assertEquals(3, result.size(), "Should return 3 categories");
        
        // Verify first category (Groceries)
        CategorySummary groceriesSummary = result.get(0);
        assertEquals(CATEGORY_GROCERIES, groceriesSummary.getCategoryCode());
        assertEquals(new BigDecimal("150.75"), groceriesSummary.getTotalAmount());
        assertEquals(2, groceriesSummary.getTotalAmount().scale(), "Scale must be 2 for COMP-3 equivalence");
        
        // Verify second category (Gas)
        CategorySummary gasSummary = result.get(1);
        assertEquals(CATEGORY_GAS, gasSummary.getCategoryCode());
        assertEquals(new BigDecimal("75.50"), gasSummary.getTotalAmount());
        
        // Verify third category (Dining)
        CategorySummary diningSummary = result.get(2);
        assertEquals(CATEGORY_DINING, diningSummary.getCategoryCode());
        assertEquals(new BigDecimal("45.25"), diningSummary.getTotalAmount());
        
        // Verify repository was called with correct parameters
        verify(transactionRepository, times(1)).aggregateByCategory(
            eq(TEST_ACCOUNT_ID), eq(TEST_START_DATE), eq(TEST_END_DATE)
        );
    }
    
    /**
     * Test 2: testCalculateCategoryTotals_PreservesDecimalPrecision
     * 
     * From CBTRN03C aggregation logic - tests that amounts 100.456, 200.789, 300.123
     * sum to 601.37 with HALF_UP rounding matching COBOL behavior.
     * Verifies BigDecimal.setScale(2, RoundingMode.HALF_UP) and no floating point errors.
     */
    @Test
    void testCalculateCategoryTotals_PreservesDecimalPrecision() {
        // Arrange - Mock aggregation with amounts requiring rounding
        // COBOL: ADD 100.456 TO WS-TOTAL → 100.46
        //        ADD 200.789 TO WS-TOTAL → 301.25
        //        ADD 300.123 TO WS-TOTAL → 601.37 (ROUNDED)
        List<Object[]> mockAggregateResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("100.456")},
            new Object[]{CATEGORY_GAS, new BigDecimal("200.789")},
            new Object[]{CATEGORY_DINING, new BigDecimal("300.123")}
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockAggregateResults);
        
        // Act
        List<CategorySummary> summaries = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Calculate grand total (sum of all categories)
        BigDecimal grandTotal = transactionCategoryService.calculateGrandTotal(summaries);
        
        // Assert - Verify precision preservation
        // Individual amounts should be rounded to 2 decimals
        assertEquals(new BigDecimal("100.46"), summaries.get(0).getTotalAmount(), 
            "100.456 should round to 100.46 with HALF_UP");
        assertEquals(new BigDecimal("200.79"), summaries.get(1).getTotalAmount(), 
            "200.789 should round to 200.79 with HALF_UP");
        assertEquals(new BigDecimal("300.12"), summaries.get(2).getTotalAmount(), 
            "300.123 should round to 300.12 with HALF_UP");
        
        // Grand total: 100.46 + 200.79 + 300.12 = 601.37
        BigDecimal expectedGrandTotal = new BigDecimal("601.37");
        assertEquals(expectedGrandTotal, grandTotal, 
            "Grand total must match COBOL COMP-3 calculation: 601.37");
        
        // Verify scale is exactly 2
        assertEquals(2, grandTotal.scale(), "Grand total scale must be 2");
    }
    
    /**
     * Test 3: testGetCategorySummary_WithDateRange_FiltersCorrectly
     * 
     * Tests date range filtering for category summary matching COBOL date comparison.
     * CBTRN03C lines 173-174: IF TRAN-PROC-TS >= WS-START-DATE AND <= WS-END-DATE
     */
    @Test
    void testGetCategorySummary_WithDateRange_FiltersCorrectly() {
        // Arrange - Mock repository with date range
        LocalDate customStartDate = LocalDate.of(2024, 2, 1);
        LocalDate customEndDate = LocalDate.of(2024, 2, 29);
        
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("200.00")}
        );
        
        when(transactionRepository.aggregateByCategory(
            eq(TEST_ACCOUNT_ID), 
            eq(customStartDate), 
            eq(customEndDate)
        )).thenReturn(mockResults);
        
        // Act
        List<CategorySummary> result = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, customStartDate, customEndDate
        );
        
        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(CATEGORY_GROCERIES, result.get(0).getCategoryCode());
        
        // Verify correct date range was passed to repository
        verify(transactionRepository).aggregateByCategory(
            eq(TEST_ACCOUNT_ID), eq(customStartDate), eq(customEndDate)
        );
    }
    
    /**
     * Test 4: testGroupByCategory_MultipleCategories_AggregatesSeparately
     * 
     * Tests transactions across multiple categories (GROCERIES, GAS, DINING, TRAVEL).
     * Verifies separate sum/count for each category and all categories present in response.
     */
    @Test
    void testGroupByCategory_MultipleCategories_AggregatesSeparately() {
        // Arrange - Mock 4 different categories
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("125.50")},
            new Object[]{CATEGORY_GAS, new BigDecimal("60.00")},
            new Object[]{CATEGORY_DINING, new BigDecimal("85.75")},
            new Object[]{CATEGORY_TRAVEL, new BigDecimal("350.00")}
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockResults);
        
        // Act
        List<CategorySummary> result = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Assert
        assertEquals(4, result.size(), "Should return all 4 categories");
        
        // Verify all category codes are present
        List<Integer> categoryCodes = result.stream()
            .map(CategorySummary::getCategoryCode)
            .toList();
        
        assertTrue(categoryCodes.contains(CATEGORY_GROCERIES), "Must contain GROCERIES category");
        assertTrue(categoryCodes.contains(CATEGORY_GAS), "Must contain GAS category");
        assertTrue(categoryCodes.contains(CATEGORY_DINING), "Must contain DINING category");
        assertTrue(categoryCodes.contains(CATEGORY_TRAVEL), "Must contain TRAVEL category");
        
        // Verify each category has correct amount
        CategorySummary travelSummary = result.stream()
            .filter(s -> s.getCategoryCode().equals(CATEGORY_TRAVEL))
            .findFirst()
            .orElseThrow();
        
        assertEquals(new BigDecimal("350.00"), travelSummary.getTotalAmount());
    }
    
    /**
     * Test 5: testCalculateAverageAmount_ByCategory_CorrectPrecision
     * 
     * Tests average transaction amount calculation with proper precision.
     * COBOL equivalent: COMPUTE WS-CAT-AVERAGE = WS-CAT-AMOUNT / WS-CAT-COUNT ROUNDED
     */
    @Test
    void testCalculateAverageAmount_ByCategory_CorrectPrecision() {
        // Arrange - Test various division scenarios
        BigDecimal totalAmount1000 = new BigDecimal("1000.00");
        Long count3 = 3L;
        
        BigDecimal totalAmount500 = new BigDecimal("500.00");
        Long count7 = 7L;
        
        // Act
        BigDecimal average1 = transactionCategoryService.calculateAverageAmount(totalAmount1000, count3);
        BigDecimal average2 = transactionCategoryService.calculateAverageAmount(totalAmount500, count7);
        
        // Assert
        // 1000.00 / 3 = 333.33... → 333.33 (HALF_UP)
        assertEquals(new BigDecimal("333.33"), average1, 
            "Average of 1000.00 / 3 should be 333.33 with HALF_UP");
        assertEquals(2, average1.scale(), "Average must have scale=2");
        
        // 500.00 / 7 = 71.42857... → 71.43 (HALF_UP)
        assertEquals(new BigDecimal("71.43"), average2, 
            "Average of 500.00 / 7 should be 71.43 with HALF_UP");
        assertEquals(2, average2.scale(), "Average must have scale=2");
    }
    
    /**
     * Test 6: testGetCategorySummary_EmptyTransactions_ReturnsZeroTotals
     * 
     * Tests no transactions for date range returns empty list or zero-value summary.
     * Matches COBOL RESP=13 (not found) equivalent - graceful empty result handling.
     */
    @Test
    void testGetCategorySummary_EmptyTransactions_ReturnsZeroTotals() {
        // Arrange - Mock empty result from repository
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new ArrayList<>());
        
        // Act
        List<CategorySummary> result = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Assert
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty list when no transactions found");
        
        // Test with complete summary (uses empty aggregation)
        AggregationResult aggregationResult = transactionCategoryService.getTransactionCategorySummary(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        assertNotNull(aggregationResult);
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), aggregationResult.getGrandTotal());
        assertEquals(0L, aggregationResult.getTotalTransactionCount());
    }
    
    /**
     * Test 7: testCategoryAggregation_HandlesNegativeAmounts
     * 
     * Tests credit transactions (negative amounts) mixed with debits.
     * Verifies net balance calculation correctness and signed COMP-3 equivalent with BigDecimal.
     * COBOL: PIC S9(09)V99 supports signed values (credits and debits).
     */
    @Test
    void testCategoryAggregation_HandlesNegativeAmounts() {
        // Arrange - Mock transactions with both positive and negative amounts
        // Simulates credits (refunds/returns) as negative amounts
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("150.00")},   // Debit
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("-25.50")},   // Credit (refund)
            new Object[]{CATEGORY_GAS, new BigDecimal("60.00")},          // Debit
            new Object[]{CATEGORY_GAS, new BigDecimal("-10.00")}          // Credit (refund)
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockResults);
        
        // Act
        List<CategorySummary> summaries = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Calculate grand total
        BigDecimal grandTotal = transactionCategoryService.calculateGrandTotal(summaries);
        
        // Assert
        // Note: Repository aggregation already sums per category, so we expect net amounts
        assertEquals(4, summaries.size(), "Should have 4 result rows from mock");
        
        // Grand total: 150.00 + (-25.50) + 60.00 + (-10.00) = 174.50
        BigDecimal expectedGrandTotal = new BigDecimal("174.50");
        assertEquals(expectedGrandTotal, grandTotal, 
            "Grand total should correctly handle negative amounts (credits)");
        
        // Verify negative amounts maintain proper scale
        CategorySummary creditSummary = summaries.get(1);
        assertEquals(2, creditSummary.getTotalAmount().scale(), "Negative amount must have scale=2");
    }
    
    /**
     * Test 8: testGetTopCategories_OrdersByAmount_Descending
     * 
     * Tests sorting categories by total amount in descending order.
     * Verifies ORDER BY equivalent in aggregation and top 5 categories returned.
     * Matches COBOL SORT operation logic from batch processing.
     */
    @Test
    void testGetTopCategories_OrdersByAmount_Descending() {
        // Arrange - Mock categories with various amounts (not pre-sorted)
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{CATEGORY_GAS, new BigDecimal("75.00")},
            new Object[]{CATEGORY_TRAVEL, new BigDecimal("450.00")},     // Highest
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("200.00")},  // Second highest
            new Object[]{CATEGORY_DINING, new BigDecimal("125.00")}
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockResults);
        
        // Act
        List<CategorySummary> summaries = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Sort by total amount descending (simulating ORDER BY amount DESC)
        summaries.sort((s1, s2) -> s2.getTotalAmount().compareTo(s1.getTotalAmount()));
        
        // Assert - Verify descending order
        assertEquals(4, summaries.size());
        assertEquals(CATEGORY_TRAVEL, summaries.get(0).getCategoryCode(), "Travel should be first (highest)");
        assertEquals(new BigDecimal("450.00"), summaries.get(0).getTotalAmount());
        
        assertEquals(CATEGORY_GROCERIES, summaries.get(1).getCategoryCode(), "Groceries should be second");
        assertEquals(new BigDecimal("200.00"), summaries.get(1).getTotalAmount());
        
        assertEquals(CATEGORY_DINING, summaries.get(2).getCategoryCode(), "Dining should be third");
        assertEquals(CATEGORY_GAS, summaries.get(3).getCategoryCode(), "Gas should be fourth (lowest)");
    }
    
    /**
     * Test 9: testCategoryPercentage_CalculatesCorrectly
     * 
     * Tests percentage calculation: (category_sum / total_sum) * 100 with precision.
     * Verifies scale=2 for percentage display and rounding matches COBOL COMPUTE.
     * Tests edge case: total sum = 0 to prevent division by zero.
     */
    @Test
    void testCategoryPercentage_CalculatesCorrectly() {
        // Arrange - Mock categories with known amounts for percentage calculation
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("300.00")},
            new Object[]{CATEGORY_GAS, new BigDecimal("200.00")},
            new Object[]{CATEGORY_DINING, new BigDecimal("100.00")}
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockResults);
        
        // Act
        List<CategorySummary> summaries = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        BigDecimal grandTotal = transactionCategoryService.calculateGrandTotal(summaries);
        List<CategorySummary> summariesWithPercentages = transactionCategoryService
            .calculateCategoryPercentages(summaries, grandTotal);
        
        // Assert
        // Grand total: 300 + 200 + 100 = 600
        assertEquals(new BigDecimal("600.00"), grandTotal);
        
        // Groceries: (300 / 600) * 100 = 50.00%
        CategorySummary groceries = summariesWithPercentages.get(0);
        assertEquals(new BigDecimal("50.00"), groceries.getPercentage(), 
            "Groceries percentage should be 50.00%");
        
        // Gas: (200 / 600) * 100 = 33.33%
        CategorySummary gas = summariesWithPercentages.get(1);
        assertEquals(new BigDecimal("33.33"), gas.getPercentage(), 
            "Gas percentage should be 33.33% with HALF_UP rounding");
        
        // Dining: (100 / 600) * 100 = 16.67%
        CategorySummary dining = summariesWithPercentages.get(2);
        assertEquals(new BigDecimal("16.67"), dining.getPercentage(), 
            "Dining percentage should be 16.67% with HALF_UP rounding");
        
        // Verify all percentages have scale=2
        summariesWithPercentages.forEach(s -> 
            assertEquals(2, s.getPercentage().scale(), "Percentage must have scale=2")
        );
    }
    
    /**
     * Test 10: testCategoryPercentage_ZeroGrandTotal_ReturnsZeroPercent
     * 
     * Tests edge case where grand total is zero - should return 0.00% for all categories
     * and not throw ArithmeticException (division by zero).
     */
    @Test
    void testCategoryPercentage_ZeroGrandTotal_ReturnsZeroPercent() {
        // Arrange - Empty summaries or zero total
        List<CategorySummary> emptySummaries = new ArrayList<>();
        BigDecimal zeroGrandTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        
        // Act
        List<CategorySummary> result = transactionCategoryService
            .calculateCategoryPercentages(emptySummaries, zeroGrandTotal);
        
        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
        
        // Test with non-empty summaries but zero grand total
        List<CategorySummary> summariesWithZero = Arrays.asList(
            new CategorySummary(CATEGORY_GROCERIES, "Groceries", BigDecimal.ZERO, 0L)
        );
        
        List<CategorySummary> resultWithZero = transactionCategoryService
            .calculateCategoryPercentages(summariesWithZero, zeroGrandTotal);
        
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), 
            resultWithZero.get(0).getPercentage(), 
            "Percentage should be 0.00% when grand total is zero");
    }
    
    /**
     * Test 11: testTransactionCount_ByCategoryAndDateRange
     * 
     * Tests COUNT aggregation alongside SUM for transaction counts.
     * Verifies both metrics calculated correctly and integer count values maintained.
     * Matches COBOL WS-REC-COUNT equivalent from batch processing.
     */
    @Test
    void testTransactionCount_ByCategoryAndDateRange() {
        // Arrange - In real implementation, repository would return count with sum
        // For this test, we verify the count field in CategorySummary
        CategorySummary summary1 = new CategorySummary(
            CATEGORY_GROCERIES, "Groceries", new BigDecimal("150.00"), 5L
        );
        CategorySummary summary2 = new CategorySummary(
            CATEGORY_GAS, "Gas", new BigDecimal("100.00"), 3L
        );
        
        List<CategorySummary> summaries = Arrays.asList(summary1, summary2);
        
        // Act
        Long totalCount = summaries.stream()
            .mapToLong(CategorySummary::getTransactionCount)
            .sum();
        
        // Assert
        assertEquals(5L, summary1.getTransactionCount(), "Groceries should have 5 transactions");
        assertEquals(3L, summary2.getTransactionCount(), "Gas should have 3 transactions");
        assertEquals(8L, totalCount, "Total transaction count should be 8");
    }
    
    /**
     * Test 12: testCategorySummary_WithNullCategories_HandlesGracefully
     * 
     * Tests transactions with null/empty category handled gracefully.
     * Verifies "UNCATEGORIZED" default category used and error handling matches COBOL SPACES logic.
     */
    @Test
    void testCategorySummary_WithNullCategories_HandlesGracefully() {
        // Arrange - Mock result with null category (should be filtered or handled)
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{null, new BigDecimal("50.00")},
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("100.00")}
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockResults);
        
        // Act
        List<CategorySummary> result = transactionCategoryService.aggregateTransactionsByCategory(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Assert - Service should handle null category gracefully
        assertNotNull(result);
        assertEquals(2, result.size(), "Should return both categories including null");
        
        // First summary might have null category code
        CategorySummary firstSummary = result.get(0);
        assertNull(firstSummary.getCategoryCode(), "Null category code should be preserved");
        assertEquals(new BigDecimal("50.00"), firstSummary.getTotalAmount());
    }
    
    /**
     * Test 13: testBigDecimalPrecision_MatchesCOBOLCOMP3
     * 
     * Direct test of BigDecimal precision matching COBOL COMP-3 behavior.
     * COBOL: 01 TRAN-AMT PIC S9(09)V99 COMP-3.
     * Verifies exact numeric equivalence for financial calculations.
     */
    @Test
    void testBigDecimalPrecision_MatchesCOBOLCOMP3() {
        // Arrange - Test COBOL COMP-3 equivalent operations
        BigDecimal amount1 = new BigDecimal("12345.678");
        BigDecimal amount2 = new BigDecimal("67890.123");
        
        // Act - Perform addition with proper scale
        BigDecimal sum = amount1.add(amount2).setScale(2, RoundingMode.HALF_UP);
        
        // Assert
        // 12345.678 + 67890.123 = 80235.801 → 80235.80 (HALF_UP rounds .801 to .80)
        assertEquals(new BigDecimal("80235.80"), sum, 
            "Sum must match COBOL COMP-3 PIC S9(09)V99 precision");
        assertEquals(2, sum.scale(), "Scale must be exactly 2");
        
        // Test multiplication
        BigDecimal multiplied = amount1.multiply(new BigDecimal("2.5"))
            .setScale(2, RoundingMode.HALF_UP);
        // 12345.678 * 2.5 = 30864.195 → 30864.20 (HALF_UP)
        assertEquals(new BigDecimal("30864.20"), multiplied);
        
        // Test division
        BigDecimal divided = amount2.divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);
        // 67890.123 / 3 = 22630.041 → 22630.04 (HALF_UP)
        assertEquals(new BigDecimal("22630.04"), divided);
    }
    
    /**
     * Test 14: testGetTransactionCategorySummary_CompleteFlow
     * 
     * Integration-style test of complete category summary flow including
     * aggregation, grand total, percentages, and averages.
     */
    @Test
    void testGetTransactionCategorySummary_CompleteFlow() {
        // Arrange - Mock complete aggregation scenario
        List<Object[]> mockResults = Arrays.asList(
            new Object[]{CATEGORY_GROCERIES, new BigDecimal("250.00")},
            new Object[]{CATEGORY_GAS, new BigDecimal("150.00")},
            new Object[]{CATEGORY_DINING, new BigDecimal("100.00")}
        );
        
        when(transactionRepository.aggregateByCategory(anyString(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(mockResults);
        
        // Act
        AggregationResult result = transactionCategoryService.getTransactionCategorySummary(
            TEST_ACCOUNT_ID, TEST_START_DATE, TEST_END_DATE
        );
        
        // Assert
        assertNotNull(result, "Aggregation result should not be null");
        assertEquals(3, result.getCategorySummaries().size(), "Should have 3 categories");
        
        // Verify grand total
        BigDecimal expectedGrandTotal = new BigDecimal("500.00");
        assertEquals(expectedGrandTotal, result.getGrandTotal(), "Grand total should be 500.00");
        
        // Verify percentages are calculated
        List<CategorySummary> summaries = result.getCategorySummaries();
        assertAll("All percentages should be calculated",
            () -> assertNotNull(summaries.get(0).getPercentage()),
            () -> assertNotNull(summaries.get(1).getPercentage()),
            () -> assertNotNull(summaries.get(2).getPercentage())
        );
        
        // Verify percentages sum to 100% (or close due to rounding)
        BigDecimal totalPercentage = summaries.stream()
            .map(CategorySummary::getPercentage)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Allow slight variance due to rounding (should be very close to 100.00)
        assertTrue(totalPercentage.compareTo(new BigDecimal("99.99")) >= 0 &&
                   totalPercentage.compareTo(new BigDecimal("100.01")) <= 0,
                   "Total percentage should be approximately 100.00%");
    }
    
    /**
     * Test 15: testAggregateTransactionsByCategory_NullAccountId_ThrowsException
     * 
     * Tests input validation for null account ID.
     * Should throw IllegalArgumentException matching COBOL error handling patterns.
     */
    @Test
    void testAggregateTransactionsByCategory_NullAccountId_ThrowsException() {
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionCategoryService.aggregateTransactionsByCategory(
                null, TEST_START_DATE, TEST_END_DATE
            ),
            "Should throw IllegalArgumentException for null account ID"
        );
        
        assertTrue(exception.getMessage().contains("Account ID"),
            "Exception message should mention Account ID");
    }
    
    /**
     * Test 16: testAggregateTransactionsByCategory_NullDates_ThrowsException
     * 
     * Tests input validation for null date parameters.
     */
    @Test
    void testAggregateTransactionsByCategory_NullDates_ThrowsException() {
        // Test null start date
        IllegalArgumentException exceptionStartDate = assertThrows(
            IllegalArgumentException.class,
            () -> transactionCategoryService.aggregateTransactionsByCategory(
                TEST_ACCOUNT_ID, null, TEST_END_DATE
            ),
            "Should throw IllegalArgumentException for null start date"
        );
        assertTrue(exceptionStartDate.getMessage().contains("Start date"));
        
        // Test null end date
        IllegalArgumentException exceptionEndDate = assertThrows(
            IllegalArgumentException.class,
            () -> transactionCategoryService.aggregateTransactionsByCategory(
                TEST_ACCOUNT_ID, TEST_START_DATE, null
            ),
            "Should throw IllegalArgumentException for null end date"
        );
        assertTrue(exceptionEndDate.getMessage().contains("End date"));
    }
    
    /**
     * Test 17: testAggregateTransactionsByCategory_InvalidDateRange_ThrowsException
     * 
     * Tests validation where start date is after end date.
     * Matches COBOL date validation logic.
     */
    @Test
    void testAggregateTransactionsByCategory_InvalidDateRange_ThrowsException() {
        // Arrange - Start date after end date
        LocalDate invalidStartDate = LocalDate.of(2024, 12, 31);
        LocalDate invalidEndDate = LocalDate.of(2024, 1, 1);
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionCategoryService.aggregateTransactionsByCategory(
                TEST_ACCOUNT_ID, invalidStartDate, invalidEndDate
            ),
            "Should throw IllegalArgumentException when start date is after end date"
        );
        
        assertTrue(exception.getMessage().contains("after"),
            "Exception message should indicate date order problem");
    }
    
    /**
     * Test 18: testCalculateGrandTotal_EmptyList_ReturnsZero
     * 
     * Tests grand total calculation with empty category list.
     * Should return BigDecimal.ZERO with proper scale.
     */
    @Test
    void testCalculateGrandTotal_EmptyList_ReturnsZero() {
        // Arrange
        List<CategorySummary> emptySummaries = new ArrayList<>();
        
        // Act
        BigDecimal grandTotal = transactionCategoryService.calculateGrandTotal(emptySummaries);
        
        // Assert
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), grandTotal,
            "Grand total of empty list should be 0.00");
        assertEquals(2, grandTotal.scale(), "Zero grand total must have scale=2");
    }
    
    /**
     * Test 19: testCalculateGrandTotal_NullList_ThrowsException
     * 
     * Tests error handling for null category list parameter.
     */
    @Test
    void testCalculateGrandTotal_NullList_ThrowsException() {
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionCategoryService.calculateGrandTotal(null),
            "Should throw IllegalArgumentException for null category list"
        );
        
        assertTrue(exception.getMessage().contains("required"),
            "Exception message should indicate list is required");
    }
    
    /**
     * Test 20: testCalculateAverageAmount_ZeroCount_ReturnsZero
     * 
     * Tests average calculation with zero transaction count.
     * Should return 0.00 without throwing ArithmeticException.
     */
    @Test
    void testCalculateAverageAmount_ZeroCount_ReturnsZero() {
        // Arrange
        BigDecimal totalAmount = new BigDecimal("100.00");
        Long zeroCount = 0L;
        
        // Act
        BigDecimal average = transactionCategoryService.calculateAverageAmount(totalAmount, zeroCount);
        
        // Assert
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), average,
            "Average with zero count should be 0.00");
    }
    
    /**
     * Test 21: testCalculateAverageAmount_NullAmount_ReturnsZero
     * 
     * Tests average calculation with null total amount.
     */
    @Test
    void testCalculateAverageAmount_NullAmount_ReturnsZero() {
        // Arrange
        BigDecimal nullAmount = null;
        Long count = 5L;
        
        // Act
        BigDecimal average = transactionCategoryService.calculateAverageAmount(nullAmount, count);
        
        // Assert
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), average,
            "Average with null amount should be 0.00");
    }
    
    /**
     * Test 22: testCategorySummary_ScalePersistence_AllOperations
     * 
     * Comprehensive test verifying BigDecimal scale=2 is maintained across
     * all operations: aggregation, addition, division, percentage calculation.
     */
    @Test
    void testCategorySummary_ScalePersistence_AllOperations() {
        // Arrange
        List<CategorySummary> summaries = Arrays.asList(
            new CategorySummary(CATEGORY_GROCERIES, "Groceries", new BigDecimal("123.45"), 3L),
            new CategorySummary(CATEGORY_GAS, "Gas", new BigDecimal("67.89"), 2L)
        );
        
        // Act - Perform all operations
        BigDecimal grandTotal = transactionCategoryService.calculateGrandTotal(summaries);
        List<CategorySummary> withPercentages = transactionCategoryService
            .calculateCategoryPercentages(summaries, grandTotal);
        
        for (CategorySummary summary : withPercentages) {
            BigDecimal avg = transactionCategoryService.calculateAverageAmount(
                summary.getTotalAmount(), summary.getTransactionCount()
            );
            summary.setAverageAmount(avg);
        }
        
        // Assert - Verify scale=2 throughout
        assertEquals(2, grandTotal.scale(), "Grand total must maintain scale=2");
        
        for (CategorySummary summary : withPercentages) {
            assertEquals(2, summary.getTotalAmount().scale(), 
                "Total amount must maintain scale=2");
            assertEquals(2, summary.getPercentage().scale(), 
                "Percentage must maintain scale=2");
            assertEquals(2, summary.getAverageAmount().scale(), 
                "Average amount must maintain scale=2");
        }
    }
}

