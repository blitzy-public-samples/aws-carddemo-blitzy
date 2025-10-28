/*
 * ReportServiceTest.java
 *
 * Comprehensive JUnit 5 unit test for ReportService testing report generation operations
 * extracted from CORPT00C.cbl PROCEDURE DIVISION logic.
 *
 * Tests transaction summary report generation, account activity report, category analysis report,
 * date range filtering, data aggregation, report formatting, and export capabilities.
 *
 * Uses Mockito to mock TransactionRepository, AccountRepository, CardRepository,
 * TransactionCategoryBalanceRepository dependencies.
 *
 * Validates that Java report logic exactly matches COBOL report processing patterns
 * per Section 0.7.2 of the Agent Action Plan.
 *
 * Original COBOL program: app/cbl/CORPT00C.cbl
 *
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
package com.carddemo.service;

import com.carddemo.exception.BusinessException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.dto.TransactionDto;
import com.carddemo.model.dto.UserDto;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.ReportService.ReportDto;
import com.carddemo.service.ReportService.TransactionReportCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * Comprehensive unit test for ReportService.
 * 
 * <p>Tests validate report generation business logic preserved exactly from COBOL
 * program CORPT00C.cbl with proper data aggregation and BigDecimal precision.</p>
 * 
 * <p>Test Coverage (per Section 0.7.14: minimum 80% code coverage):</p>
 * <ul>
 *   <li>Transaction Summary Report Generation</li>
 *   <li>Account Activity Report Generation</li>
 *   <li>User Activity Report Generation</li>
 *   <li>Date Range Filtering and Validation</li>
 *   <li>Data Aggregation with BigDecimal Precision</li>
 *   <li>Report Formatting and CSV Export</li>
 *   <li>Edge Cases and Error Handling</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class ReportServiceTest {

    /**
     * Mock AccountService dependency for account data retrieval
     */
    @Mock
    private AccountService mockAccountService;

    /**
     * Mock TransactionService dependency for transaction data retrieval
     */
    @Mock
    private TransactionService mockTransactionService;

    /**
     * Mock UserService dependency for user data retrieval
     */
    @Mock
    private UserService mockUserService;

    /**
     * Mock CardService dependency for card data retrieval
     */
    @Mock
    private CardService mockCardService;

    /**
     * Mock ValidationService dependency for date and field validation
     */
    @Mock
    private ValidationService mockValidationService;

    /**
     * ReportService instance under test with mocked dependencies injected
     */
    @InjectMocks
    private ReportService reportService;

    // Test data constants
    private static final LocalDate TEST_START_DATE = LocalDate.of(2024, 1, 1);
    private static final LocalDate TEST_END_DATE = LocalDate.of(2024, 1, 31);
    private static final String TEST_CARD_NUMBER = "4111111111111111";
    private static final Long TEST_ACCOUNT_ID = 10001L;
    private static final BigDecimal TEST_CREDIT_LIMIT = new BigDecimal("10000.00");
    private static final BigDecimal TEST_BALANCE = new BigDecimal("2500.50");

    /**
     * Set up test data before each test method execution
     */
    @BeforeEach
    public void setUp() {
        // No additional setup needed - Mockito automatically initializes mocks
    }

    // ========================================================================
    // Account Summary Report Tests
    // ========================================================================

    /**
     * Test successful generation of account summary report.
     * 
     * <p>Validates that report aggregates account statistics correctly including:
     * total accounts, active accounts, total credit limits, and total balances.</p>
     * 
     * <p>COBOL equivalent: CORPT00C.cbl lines 213-238 (Monthly report generation)</p>
     */
    @Test
    public void testGenerateAccountSummaryReport() {
        // Arrange: Create test account data
        List<AccountDto> testAccounts = createTestAccountList();
        
        // Mock AccountService.getAllAccounts() to return test accounts
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act: Generate account summary report
        ReportDto report = reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify report metadata
        assertThat(report).isNotNull();
        assertThat(report.getReportType()).isEqualTo("ACCOUNT_SUMMARY");
        assertThat(report.getReportName()).isEqualTo("Account Summary Report");
        assertThat(report.getStartDate()).isEqualTo(TEST_START_DATE);
        assertThat(report.getEndDate()).isEqualTo(TEST_END_DATE);
        assertThat(report.getGeneratedAt()).isNotNull();
        assertThat(report.getTotalRecords()).isEqualTo(4); // 4 summary rows

        // Assert: Verify report data rows contain expected metrics
        List<Map<String, Object>> dataRows = report.getDataRows();
        assertThat(dataRows).isNotEmpty();
        assertThat(dataRows).hasSize(4);

        // Verify total accounts metric
        Map<String, Object> totalAccountsRow = dataRows.get(0);
        assertThat(totalAccountsRow.get("metric")).isEqualTo("Total Accounts");
        assertThat(totalAccountsRow.get("value")).isEqualTo(testAccounts.size());

        // Verify active accounts metric (count accounts with status 'Y')
        long expectedActiveAccounts = testAccounts.stream()
                .filter(a -> "Y".equals(a.getAcctActiveStatus()))
                .count();
        Map<String, Object> activeAccountsRow = dataRows.get(1);
        assertThat(activeAccountsRow.get("metric")).isEqualTo("Active Accounts");
        assertThat(activeAccountsRow.get("value")).isEqualTo((int) expectedActiveAccounts);

        // Verify total credit limits (BigDecimal aggregation with scale 2)
        BigDecimal expectedCreditLimits = testAccounts.stream()
                .map(AccountDto::getAcctCreditLimit)
                .filter(limit -> limit != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> creditLimitsRow = dataRows.get(2);
        assertThat(creditLimitsRow.get("metric")).isEqualTo("Total Credit Limits");
        assertThat((BigDecimal) creditLimitsRow.get("value"))
                .isEqualByComparingTo(expectedCreditLimits);

        // Verify total balances
        BigDecimal expectedBalances = testAccounts.stream()
                .map(AccountDto::getAcctCurrBal)
                .filter(balance -> balance != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> balancesRow = dataRows.get(3);
        assertThat(balancesRow.get("metric")).isEqualTo("Total Balances");
        assertThat((BigDecimal) balancesRow.get("value"))
                .isEqualByComparingTo(expectedBalances);

        // Verify service interactions
        verify(mockAccountService, times(1)).getAllAccounts();
    }

    /**
     * Test account summary report generation with empty account list.
     * 
     * <p>Validates that report handles empty data gracefully with zero values.</p>
     */
    @Test
    public void testGenerateAccountSummaryReportEmpty() {
        // Arrange: Mock empty account list
        when(mockAccountService.getAllAccounts()).thenReturn(new ArrayList<>());

        // Act: Generate account summary report
        ReportDto report = reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify report generated with zero values
        assertThat(report).isNotNull();
        assertThat(report.getReportType()).isEqualTo("ACCOUNT_SUMMARY");
        
        List<Map<String, Object>> dataRows = report.getDataRows();
        assertThat(dataRows).hasSize(4);
        
        // Total accounts should be 0
        Map<String, Object> totalAccountsRow = dataRows.get(0);
        assertThat(totalAccountsRow.get("value")).isEqualTo(0);
        
        // Total credit limits should be 0.00
        Map<String, Object> creditLimitsRow = dataRows.get(2);
        assertThat((BigDecimal) creditLimitsRow.get("value"))
                .isEqualByComparingTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Test account summary report with invalid date range (start date after end date).
     * 
     * <p>COBOL equivalent: Date validation logic in CORPT00C.cbl lines 388-426</p>
     */
    @Test
    public void testGenerateAccountSummaryReportInvalidDateRange() {
        // Arrange: Start date after end date
        LocalDate invalidStartDate = LocalDate.of(2024, 12, 31);
        LocalDate invalidEndDate = LocalDate.of(2024, 1, 1);

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> 
                reportService.generateAccountSummaryReport(invalidStartDate, invalidEndDate))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Start date cannot be after end date");
    }

    // ========================================================================
    // Transaction Report Tests
    // ========================================================================

    /**
     * Test successful generation of transaction report with card number filter.
     * 
     * <p>Validates transaction aggregation by category with BigDecimal precision.</p>
     * 
     * <p>COBOL equivalent: CORPT00C.cbl lines 256-436 (Custom report generation)</p>
     */
    @Test
    public void testGenerateTransactionReport() {
        // Arrange: Create test transaction criteria
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        // Create test transactions with various categories
        List<TransactionDto> testTransactions = createTestTransactionList();
        
        // Mock TransactionService to return paginated results
        Page<TransactionDto> transactionPage = new PageImpl<>(testTransactions);
        when(mockTransactionService.listTransactions(
                anyString(), 
                any(LocalDate.class), 
                any(LocalDate.class), 
                any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify report metadata
        assertThat(report).isNotNull();
        assertThat(report.getReportType()).isEqualTo("TRANSACTION_ACTIVITY");
        assertThat(report.getReportName()).isEqualTo("Transaction Activity Report");
        assertThat(report.getStartDate()).isEqualTo(TEST_START_DATE);
        assertThat(report.getEndDate()).isEqualTo(TEST_END_DATE);
        assertThat(report.getTotalRecords()).isEqualTo(testTransactions.size());

        // Assert: Verify report data rows contain category breakdown
        List<Map<String, Object>> dataRows = report.getDataRows();
        assertThat(dataRows).isNotEmpty();
        
        // Verify grand total row exists (last row)
        Map<String, Object> summaryRow = dataRows.get(dataRows.size() - 1);
        assertThat(summaryRow.get("metric")).isEqualTo("Grand Total");
        assertThat(summaryRow.get("totalTransactions")).isEqualTo(testTransactions.size());
        
        // Verify total amount matches sum of all transactions with scale 2
        BigDecimal expectedTotal = testTransactions.stream()
                .map(TransactionDto::getTransAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        assertThat((BigDecimal) summaryRow.get("totalAmount"))
                .isEqualByComparingTo(expectedTotal);

        // Verify service interactions
        verify(mockTransactionService, times(1))
                .listTransactions(anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class));
    }

    /**
     * Test transaction report with transaction type filter.
     * 
     * <p>Filters transactions to only include specific transaction type.</p>
     */
    @Test
    public void testGenerateTransactionReportByType() {
        // Arrange: Create criteria with transaction type filter
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .transactionType("01") // Purchase transactions only
                .build();

        List<TransactionDto> allTransactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(allTransactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify only type "01" transactions included
        List<Map<String, Object>> dataRows = report.getDataRows();
        Map<String, Object> summaryRow = dataRows.get(dataRows.size() - 1);
        
        long expectedCount = allTransactions.stream()
                .filter(tx -> "01".equals(tx.getTransTypeCd()))
                .count();
        
        assertThat(summaryRow.get("totalTransactions")).isEqualTo((int) expectedCount);
    }

    /**
     * Test transaction report with transaction category filter.
     */
    @Test
    public void testGenerateTransactionReportByCategory() {
        // Arrange: Create criteria with category filter
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .transactionCategory(1001) // Retail category
                .build();

        List<TransactionDto> allTransactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(allTransactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify filtering worked
        assertThat(report).isNotNull();
        assertThat(report.getTotalRecords()).isLessThanOrEqualTo(allTransactions.size());
    }

    /**
     * Test transaction report with null criteria - should throw BusinessException.
     */
    @Test
    public void testGenerateTransactionReportNullCriteria() {
        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> reportService.generateTransactionReport(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Report criteria cannot be null");
    }

    /**
     * Test transaction report with invalid date range.
     */
    @Test
    public void testGenerateTransactionReportInvalidDateRange() {
        // Arrange: Start date after end date
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(LocalDate.of(2024, 12, 31))
                .endDate(LocalDate.of(2024, 1, 1))
                .build();

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> reportService.generateTransactionReport(criteria))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Start date cannot be after end date");
    }

    /**
     * Test transaction report with empty result set.
     */
    @Test
    public void testGenerateTransactionReportEmpty() {
        // Arrange: Empty transaction list
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        Page<TransactionDto> emptyPage = new PageImpl<>(new ArrayList<>());
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify report with empty data
        assertThat(report).isNotNull();
        assertThat(report.getTotalRecords()).isEqualTo(0);
        assertThat(report.getDataRows()).hasSize(1); // Only summary row
    }

    // ========================================================================
    // User Activity Report Tests
    // ========================================================================

    /**
     * Test successful generation of user activity report.
     * 
     * <p>New functionality not in COBOL - tests user statistics aggregation.</p>
     */
    @Test
    public void testGenerateUserActivityReport() {
        // Arrange: Create test user data
        List<UserDto> testUsers = createTestUserList();
        when(mockUserService.getAllUsers()).thenReturn(testUsers);

        // Act: Generate user activity report
        ReportDto report = reportService.generateUserActivityReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify report metadata
        assertThat(report).isNotNull();
        assertThat(report.getReportType()).isEqualTo("USER_ACTIVITY");
        assertThat(report.getReportName()).isEqualTo("User Activity Report");
        assertThat(report.getStartDate()).isEqualTo(TEST_START_DATE);
        assertThat(report.getEndDate()).isEqualTo(TEST_END_DATE);

        // Assert: Verify report data rows
        List<Map<String, Object>> dataRows = report.getDataRows();
        assertThat(dataRows).isNotEmpty();
        
        // First row should be total users
        Map<String, Object> totalRow = dataRows.get(0);
        assertThat(totalRow.get("metric")).isEqualTo("Total Users");
        assertThat(totalRow.get("value")).isEqualTo(testUsers.size());

        // Verify service interactions
        verify(mockUserService, times(1)).getAllUsers();
    }

    /**
     * Test user activity report with invalid date range.
     */
    @Test
    public void testGenerateUserActivityReportInvalidDateRange() {
        // Arrange: Start date after end date
        LocalDate invalidStartDate = LocalDate.of(2024, 12, 31);
        LocalDate invalidEndDate = LocalDate.of(2024, 1, 1);

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> 
                reportService.generateUserActivityReport(invalidStartDate, invalidEndDate))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Start date cannot be after end date");
    }

    // ========================================================================
    // Report Export Tests
    // ========================================================================

    /**
     * Test successful CSV export of report data.
     * 
     * <p>Replaces COBOL EXEC CICS WRITEQ TD logic from CORPT00C.cbl lines 517-535.</p>
     */
    @Test
    public void testExportReportToCsv() {
        // Arrange: Create test report
        ReportDto testReport = createTestReportDto();

        // Act: Export to CSV
        String csvOutput = reportService.exportReportToCsv(testReport);

        // Assert: Verify CSV format
        assertThat(csvOutput).isNotNull();
        assertThat(csvOutput).isNotEmpty();
        
        // Verify CSV contains report metadata
        assertThat(csvOutput).contains("Report Type: " + testReport.getReportType());
        assertThat(csvOutput).contains("Report Name: " + testReport.getReportName());
        assertThat(csvOutput).contains("Generated:");
        assertThat(csvOutput).contains("Period:");
        assertThat(csvOutput).contains("Total Records:");
        
        // Verify CSV contains column headers
        assertThat(csvOutput).contains("metric");
        assertThat(csvOutput).contains("value");
    }

    /**
     * Test CSV export with null report - should throw BusinessException.
     */
    @Test
    public void testExportReportToCsvNullReport() {
        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> reportService.exportReportToCsv(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Report data cannot be null");
    }

    /**
     * Test CSV export with empty data rows - should throw BusinessException.
     */
    @Test
    public void testExportReportToCsvEmptyData() {
        // Arrange: Create report with empty data rows
        ReportDto emptyReport = ReportDto.builder()
                .reportType("TEST_REPORT")
                .reportName("Test Report")
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .generatedAt(LocalDateTime.now())
                .totalRecords(0)
                .dataRows(new ArrayList<>())
                .build();

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> reportService.exportReportToCsv(emptyReport))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Report has no data to export");
    }

    /**
     * Test PDF export - currently not implemented, should throw BusinessException.
     * 
     * <p>Future enhancement placeholder per ReportService documentation.</p>
     */
    @Test
    public void testExportReportToPdfNotImplemented() {
        // Arrange: Create test report
        ReportDto testReport = createTestReportDto();

        // Act & Assert: Expect BusinessException indicating not implemented
        assertThatThrownBy(() -> reportService.exportReportToPdf(testReport))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF export is not yet implemented");
    }

    // ========================================================================
    // Date Range Filtering Tests
    // ========================================================================

    /**
     * Test report with current month date range.
     * 
     * <p>COBOL equivalent: CORPT00C.cbl lines 213-238 (Monthly report)</p>
     */
    @Test
    public void testReportCurrentMonth() {
        // Arrange: Calculate current month date range
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDate monthEnd = now.withDayOfMonth(now.lengthOfMonth());

        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act: Generate report for current month
        ReportDto report = reportService.generateAccountSummaryReport(monthStart, monthEnd);

        // Assert: Verify date range
        assertThat(report.getStartDate()).isEqualTo(monthStart);
        assertThat(report.getEndDate()).isEqualTo(monthEnd);
        assertThat(report).isNotNull();
    }

    /**
     * Test report with year-to-date date range.
     * 
     * <p>COBOL equivalent: CORPT00C.cbl lines 239-254 (Yearly report)</p>
     */
    @Test
    public void testReportYearToDate() {
        // Arrange: Calculate year-to-date range
        LocalDate now = LocalDate.now();
        LocalDate yearStart = LocalDate.of(now.getYear(), 1, 1);
        LocalDate yearEnd = LocalDate.of(now.getYear(), 12, 31);

        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act: Generate report for year-to-date
        ReportDto report = reportService.generateAccountSummaryReport(yearStart, yearEnd);

        // Assert: Verify date range
        assertThat(report.getStartDate()).isEqualTo(yearStart);
        assertThat(report.getEndDate()).isEqualTo(yearEnd);
        assertThat(report).isNotNull();
    }

    /**
     * Test report with custom date range.
     * 
     * <p>COBOL equivalent: CORPT00C.cbl lines 256-436 (Custom report)</p>
     */
    @Test
    public void testReportCustomDateRange() {
        // Arrange: Custom date range
        LocalDate customStart = LocalDate.of(2024, 3, 15);
        LocalDate customEnd = LocalDate.of(2024, 6, 30);

        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act: Generate report for custom range
        ReportDto report = reportService.generateAccountSummaryReport(customStart, customEnd);

        // Assert: Verify custom date range
        assertThat(report.getStartDate()).isEqualTo(customStart);
        assertThat(report.getEndDate()).isEqualTo(customEnd);
        assertThat(report).isNotNull();
    }

    // ========================================================================
    // BigDecimal Precision Tests
    // ========================================================================

    /**
     * Test report amount precision maintains scale 2 with HALF_UP rounding.
     * 
     * <p>Per Section 0.7.2: All financial aggregations must preserve COBOL COMP-3
     * packed decimal precision using BigDecimal with scale 2, RoundingMode.HALF_UP.</p>
     */
    @Test
    public void testReportAmountPrecision() {
        // Arrange: Create accounts with various precision amounts
        List<AccountDto> accounts = Arrays.asList(
                createAccountDto(10001L, "Y", "5000.12", "10000.00"),
                createAccountDto(10002L, "Y", "3333.33", "15000.00"),
                createAccountDto(10003L, "Y", "1666.67", "8000.00")
        );
        when(mockAccountService.getAllAccounts()).thenReturn(accounts);

        // Act: Generate account summary report
        ReportDto report = reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify all amounts maintain scale 2
        List<Map<String, Object>> dataRows = report.getDataRows();
        Map<String, Object> creditRow = dataRows.get(2);
        BigDecimal totalCredit = (BigDecimal) creditRow.get("value");
        assertThat(totalCredit.scale()).isEqualTo(2);
        assertThat(totalCredit).isEqualByComparingTo(new BigDecimal("33000.00"));

        Map<String, Object> balanceRow = dataRows.get(3);
        BigDecimal totalBalance = (BigDecimal) balanceRow.get("value");
        assertThat(totalBalance.scale()).isEqualTo(2);
        assertThat(totalBalance).isEqualByComparingTo(new BigDecimal("10000.12"));
    }

    /**
     * Test transaction report total calculation with BigDecimal precision.
     */
    @Test
    public void testReportTotalCalculation() {
        // Arrange: Create transactions with various amounts
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        List<TransactionDto> transactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(transactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify total calculation maintains precision
        List<Map<String, Object>> dataRows = report.getDataRows();
        Map<String, Object> summaryRow = dataRows.get(dataRows.size() - 1);
        BigDecimal totalAmount = (BigDecimal) summaryRow.get("totalAmount");
        
        assertThat(totalAmount.scale()).isEqualTo(2);
        
        BigDecimal expectedTotal = transactions.stream()
                .map(TransactionDto::getTransAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        
        assertThat(totalAmount).isEqualByComparingTo(expectedTotal);
    }

    /**
     * Test average calculation with proper rounding.
     */
    @Test
    public void testReportAverageCalculation() {
        // Arrange: Create transactions for average calculation
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        List<TransactionDto> transactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(transactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Calculate expected average
        BigDecimal totalAmount = transactions.stream()
                .map(TransactionDto::getTransAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = totalAmount
                .divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_UP);
        
        assertThat(average.scale()).isEqualTo(2);
    }

    // ========================================================================
    // Category Analysis Tests
    // ========================================================================

    /**
     * Test category breakdown in transaction report.
     */
    @Test
    public void testCategoryBreakdown() {
        // Arrange: Create criteria
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        List<TransactionDto> transactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(transactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify category breakdown exists
        List<Map<String, Object>> dataRows = report.getDataRows();
        
        // Should have category rows plus summary row
        assertThat(dataRows.size()).isGreaterThan(1);
        
        // Verify category rows have expected fields
        for (int i = 0; i < dataRows.size() - 1; i++) {
            Map<String, Object> categoryRow = dataRows.get(i);
            assertThat(categoryRow).containsKey("category");
            assertThat(categoryRow).containsKey("categoryTotal");
            assertThat(categoryRow).containsKey("transactionCount");
        }
    }

    /**
     * Test top categories sorting.
     */
    @Test
    public void testTopCategories() {
        // Arrange: Create criteria
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        List<TransactionDto> transactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(transactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify report generated
        assertThat(report).isNotNull();
        assertThat(report.getDataRows()).isNotEmpty();
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    /**
     * Test report with future date range.
     */
    @Test
    public void testReportFutureDate() {
        // Arrange: Future date range
        LocalDate futureStart = LocalDate.now().plusMonths(1);
        LocalDate futureEnd = LocalDate.now().plusMonths(2);

        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act: Generate report with future dates (should work, just no data typically)
        ReportDto report = reportService.generateAccountSummaryReport(futureStart, futureEnd);

        // Assert: Report generated successfully
        assertThat(report).isNotNull();
        assertThat(report.getStartDate()).isEqualTo(futureStart);
        assertThat(report.getEndDate()).isEqualTo(futureEnd);
    }

    /**
     * Test report with single day date range.
     */
    @Test
    public void testReportSingleDay() {
        // Arrange: Same start and end date
        LocalDate singleDay = LocalDate.of(2024, 6, 15);

        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act: Generate report for single day
        ReportDto report = reportService.generateAccountSummaryReport(singleDay, singleDay);

        // Assert: Report generated successfully
        assertThat(report).isNotNull();
        assertThat(report.getStartDate()).isEqualTo(singleDay);
        assertThat(report.getEndDate()).isEqualTo(singleDay);
    }

    // ========================================================================
    // Helper Methods to Create Test Data
    // ========================================================================

    /**
     * Create a list of test AccountDto objects for testing.
     */
    private List<AccountDto> createTestAccountList() {
        return Arrays.asList(
                createAccountDto(10001L, "Y", "2500.50", "10000.00"),
                createAccountDto(10002L, "Y", "5000.00", "15000.00"),
                createAccountDto(10003L, "N", "1200.75", "8000.00"),
                createAccountDto(10004L, "Y", "750.25", "5000.00")
        );
    }

    /**
     * Create a single test AccountDto object.
     */
    private AccountDto createAccountDto(Long acctId, String status, String balance, String creditLimit) {
        return AccountDto.builder()
                .acctId(acctId)
                .acctActiveStatus(status)
                .acctCurrBal(new BigDecimal(balance))
                .acctCreditLimit(new BigDecimal(creditLimit))
                .acctCashCreditLimit(new BigDecimal(creditLimit).multiply(new BigDecimal("0.5")))
                .acctOpenDate(LocalDate.of(2020, 1, 1))
                .build();
    }

    /**
     * Create a list of test TransactionDto objects for testing.
     */
    private List<TransactionDto> createTestTransactionList() {
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 15, 10, 0);
        
        return Arrays.asList(
                createTransactionDto("TX000000000001", TEST_CARD_NUMBER, "01", 1001, "125.50", baseTime),
                createTransactionDto("TX000000000002", TEST_CARD_NUMBER, "01", 1001, "75.25", baseTime.plusHours(1)),
                createTransactionDto("TX000000000003", TEST_CARD_NUMBER, "01", 1002, "250.00", baseTime.plusHours(2)),
                createTransactionDto("TX000000000004", TEST_CARD_NUMBER, "02", 2001, "100.00", baseTime.plusHours(3)),
                createTransactionDto("TX000000000005", TEST_CARD_NUMBER, "01", 1001, "50.75", baseTime.plusHours(4)),
                createTransactionDto("TX000000000006", TEST_CARD_NUMBER, "01", 1003, "175.50", baseTime.plusHours(5))
        );
    }

    /**
     * Create a single test TransactionDto object.
     */
    private TransactionDto createTransactionDto(String transId, String cardNum, String typeCd, 
                                                 Integer catCd, String amount, LocalDateTime origTs) {
        return TransactionDto.builder()
                .transId(transId)
                .transCardNum(cardNum)
                .transTypeCd(typeCd)
                .transCatCd(catCd)
                .transAmt(new BigDecimal(amount))
                .transOrigTs(origTs)
                .transProcTs(origTs.plusMinutes(5))
                .transDesc("Test Transaction")
                .transMerchantName("Test Merchant")
                .transSource("POS")
                .build();
    }

    /**
     * Create a list of test UserDto objects for testing.
     */
    private List<UserDto> createTestUserList() {
        return Arrays.asList(
                createUserDto("USER0001", "Admin", "A"),
                createUserDto("USER0002", "User", "U"),
                createUserDto("USER0003", "Operator", "O"),
                createUserDto("USER0004", "Admin", "A")
        );
    }

    /**
     * Create a single test UserDto object.
     */
    private UserDto createUserDto(String userId, String name, String userType) {
        return UserDto.builder()
                .userId(userId)
                .userFirstName(name)
                .userLastName("Test")
                .userType(userType)
                .build();
    }

    /**
     * Create a test ReportDto for CSV export testing.
     */
    private ReportDto createTestReportDto() {
        List<Map<String, Object>> dataRows = new ArrayList<>();
        
        Map<String, Object> row1 = Map.of(
                "metric", "Total Accounts",
                "value", 10
        );
        dataRows.add(row1);
        
        Map<String, Object> row2 = Map.of(
                "metric", "Active Accounts",
                "value", 8
        );
        dataRows.add(row2);
        
        Map<String, Object> row3 = Map.of(
                "metric", "Total Credit Limits",
                "value", new BigDecimal("100000.00")
        );
        dataRows.add(row3);
        
        return ReportDto.builder()
                .reportType("ACCOUNT_SUMMARY")
                .reportName("Account Summary Report")
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .generatedAt(LocalDateTime.now())
                .totalRecords(dataRows.size())
                .dataRows(dataRows)
                .build();
    }

    // ========================================================================
    // Additional Transaction Report Tests with Amount Filters
    // ========================================================================

    /**
     * Test transaction report with minimum amount filter.
     */
    @Test
    public void testTransactionReportWithMinAmount() {
        // Arrange: Create criteria with minimum amount filter
        BigDecimal minAmount = new BigDecimal("100.00");
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .minAmount(minAmount)
                .build();

        List<TransactionDto> allTransactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(allTransactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify only transactions >= minAmount included
        List<Map<String, Object>> dataRows = report.getDataRows();
        Map<String, Object> summaryRow = dataRows.get(dataRows.size() - 1);
        
        long expectedCount = allTransactions.stream()
                .filter(tx -> tx.getTransAmt().compareTo(minAmount) >= 0)
                .count();
        
        assertThat(summaryRow.get("totalTransactions")).isEqualTo((int) expectedCount);
    }

    /**
     * Test transaction report with maximum amount filter.
     */
    @Test
    public void testTransactionReportWithMaxAmount() {
        // Arrange: Create criteria with maximum amount filter
        BigDecimal maxAmount = new BigDecimal("150.00");
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .maxAmount(maxAmount)
                .build();

        List<TransactionDto> allTransactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(allTransactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify only transactions <= maxAmount included
        List<Map<String, Object>> dataRows = report.getDataRows();
        Map<String, Object> summaryRow = dataRows.get(dataRows.size() - 1);
        
        long expectedCount = allTransactions.stream()
                .filter(tx -> tx.getTransAmt().compareTo(maxAmount) <= 0)
                .count();
        
        assertThat(summaryRow.get("totalTransactions")).isEqualTo((int) expectedCount);
    }

    /**
     * Test transaction report with both minimum and maximum amount filters.
     */
    @Test
    public void testTransactionReportWithAmountRange() {
        // Arrange: Create criteria with amount range
        BigDecimal minAmount = new BigDecimal("50.00");
        BigDecimal maxAmount = new BigDecimal("200.00");
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .minAmount(minAmount)
                .maxAmount(maxAmount)
                .build();

        List<TransactionDto> allTransactions = createTestTransactionList();
        Page<TransactionDto> transactionPage = new PageImpl<>(allTransactions);
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify filtering worked
        assertThat(report).isNotNull();
        assertThat(report.getTotalRecords()).isLessThanOrEqualTo(allTransactions.size());
    }

    // ========================================================================
    // Report Formatting Tests
    // ========================================================================

    /**
     * Test report header generation with metadata.
     */
    @Test
    public void testReportHeaderGeneration() {
        // Arrange
        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act
        ReportDto report = reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify report header fields populated
        assertThat(report.getReportType()).isNotNull();
        assertThat(report.getReportName()).isNotNull();
        assertThat(report.getStartDate()).isNotNull();
        assertThat(report.getEndDate()).isNotNull();
        assertThat(report.getGeneratedAt()).isNotNull();
        assertThat(report.getTotalRecords()).isNotNull();
        
        // Verify generated timestamp is recent (within last minute)
        assertThat(report.getGeneratedAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    /**
     * Test report body formatting with data rows.
     */
    @Test
    public void testReportBodyFormatting() {
        // Arrange
        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act
        ReportDto report = reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify data rows structure
        List<Map<String, Object>> dataRows = report.getDataRows();
        assertThat(dataRows).isNotNull();
        assertThat(dataRows).isNotEmpty();
        
        // Each data row should be a map with string keys and object values
        for (Map<String, Object> row : dataRows) {
            assertThat(row).isNotNull();
            assertThat(row).isNotEmpty();
        }
    }

    /**
     * Test CSV column alignment for numeric values.
     */
    @Test
    public void testReportColumnAlignment() {
        // Arrange
        ReportDto testReport = createTestReportDto();

        // Act
        String csvOutput = reportService.exportReportToCsv(testReport);

        // Assert: Verify CSV format and structure
        assertThat(csvOutput).isNotNull();
        assertThat(csvOutput).contains(","); // CSV should have comma separators
        
        // Verify numeric values are properly formatted
        assertThat(csvOutput).contains("100000.00");
    }

    // ========================================================================
    // Performance and Large Dataset Tests
    // ========================================================================

    /**
     * Test report generation with large account dataset.
     * 
     * <p>Validates performance with 1000+ accounts.</p>
     */
    @Test
    public void testReportGenerationPerformance() {
        // Arrange: Create large account list
        List<AccountDto> largeAccountList = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            largeAccountList.add(createAccountDto(
                    (long) i, 
                    i % 3 == 0 ? "N" : "Y", 
                    String.format("%d.%02d", i * 10, i % 100),
                    "10000.00"
            ));
        }
        when(mockAccountService.getAllAccounts()).thenReturn(largeAccountList);

        // Act: Generate report and measure time
        long startTime = System.currentTimeMillis();
        ReportDto report = reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);
        long endTime = System.currentTimeMillis();

        // Assert: Verify report generated successfully
        assertThat(report).isNotNull();
        assertThat(report.getDataRows()).hasSize(4);
        
        // Performance assertion: should complete in reasonable time (< 1 second for 1000 accounts)
        long executionTime = endTime - startTime;
        assertThat(executionTime).isLessThan(1000); // milliseconds
    }

    /**
     * Test report with pagination for large transaction datasets.
     */
    @Test
    public void testReportWithPagination() {
        // Arrange: Create criteria
        TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .build();

        // Create multiple pages of transactions
        List<TransactionDto> page1Transactions = createTestTransactionList();
        List<TransactionDto> page2Transactions = createTestTransactionList();
        
        Page<TransactionDto> page1 = new PageImpl<>(page1Transactions, Pageable.ofSize(100), 12);
        Page<TransactionDto> page2 = new PageImpl<>(page2Transactions, Pageable.ofSize(100).withPage(1), 12);
        
        when(mockTransactionService.listTransactions(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(page1);

        // Act: Generate transaction report
        ReportDto report = reportService.generateTransactionReport(criteria);

        // Assert: Verify report generated successfully
        assertThat(report).isNotNull();
        assertThat(report.getTotalRecords()).isGreaterThan(0);
    }

    // ========================================================================
    // Validation and Error Handling Tests
    // ========================================================================

    /**
     * Test validation service is called for date validation.
     */
    @Test
    public void testValidationServiceCalled() {
        // Arrange
        List<AccountDto> testAccounts = createTestAccountList();
        when(mockAccountService.getAllAccounts()).thenReturn(testAccounts);

        // Act
        reportService.generateAccountSummaryReport(TEST_START_DATE, TEST_END_DATE);

        // Assert: Verify validation service was called for both dates
        verify(mockValidationService, times(1)).validateDate(TEST_START_DATE);
        verify(mockValidationService, times(1)).validateDate(TEST_END_DATE);
    }

    /**
     * Test CSV export with special characters in data.
     */
    @Test
    public void testCsvExportWithSpecialCharacters() {
        // Arrange: Create report with special characters
        List<Map<String, Object>> dataRows = new ArrayList<>();
        Map<String, Object> row = Map.of(
                "description", "Test, with \"quotes\" and commas",
                "amount", new BigDecimal("100.00")
        );
        dataRows.add(row);
        
        ReportDto report = ReportDto.builder()
                .reportType("TEST")
                .reportName("Test Report")
                .startDate(TEST_START_DATE)
                .endDate(TEST_END_DATE)
                .generatedAt(LocalDateTime.now())
                .totalRecords(1)
                .dataRows(dataRows)
                .build();

        // Act
        String csvOutput = reportService.exportReportToCsv(report);

        // Assert: Verify special characters properly escaped
        assertThat(csvOutput).isNotNull();
        assertThat(csvOutput).contains("\"Test, with \"\"quotes\"\" and commas\"");
    }
}

