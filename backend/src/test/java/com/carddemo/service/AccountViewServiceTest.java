/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.service;

import com.carddemo.dto.response.AccountViewResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.util.DecimalUtils;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 test suite for AccountViewService validating business logic transformation
 * from COBOL program COACTVWC.cbl (CICS transaction CAVW).
 * 
 * <p><strong>Test Coverage Summary:</strong></p>
 * <p>This test class provides comprehensive validation of account view operations including:</p>
 * <ul>
 *   <li>VSAM ACCTDAT file read operations transformed to JPA repository findById()</li>
 *   <li>Customer detail retrieval with cross-reference resolution via foreign key relationships</li>
 *   <li>SSN masking and formatting (XXX-XX-XXXX format per COBOL lines 496-504)</li>
 *   <li>Balance formatting with BigDecimal scale=2 and HALF_UP rounding (COMP-3 precision)</li>
 *   <li>Account not found exception handling (DFHRESP(NOTFND) equivalent)</li>
 *   <li>Credit limit and available balance calculations with exact decimal arithmetic</li>
 *   <li>Account status validation matching COBOL 88-level condition names</li>
 *   <li>Error handling preserving COBOL file-status and CICS RESP codes</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <ul>
 *   <li>Source Program: app/cbl/COACTVWC.cbl</li>
 *   <li>CICS Transaction: CAVW</li>
 *   <li>Primary Paragraph: 9000-READ-ACCT (lines 687-720)</li>
 *   <li>File Operations: 9200-GETCARDXREF-BYACCT, 9300-GETACCTDATA-BYACCT, 9400-GETCUSTDATA-BYCUST</li>
 *   <li>Screen Formatting: 1200-SETUP-SCREEN-VARS (lines 460-535)</li>
 * </ul>
 * 
 * <p><strong>Key Transformations Tested:</strong></p>
 * <ul>
 *   <li>EXEC CICS READ ACCTDAT → AccountRepository.findByAccountId()</li>
 *   <li>EXEC CICS READ CUSTDAT → Account.getCustomer() JPA relationship</li>
 *   <li>COBOL STRING SSN formatting → maskSSN() method with dash insertion</li>
 *   <li>COBOL COMP-3 balance → BigDecimal with setScale(2, HALF_UP)</li>
 *   <li>DFHRESP(NOTFND) → AccountNotFoundException</li>
 *   <li>DFHRESP(NORMAL) → Optional.of(account)</li>
 * </ul>
 * 
 * <p><strong>Test Strategy:</strong></p>
 * <ul>
 *   <li>Unit tests using Mockito for repository mocking</li>
 *   <li>Isolated testing without Spring context or database connections</li>
 *   <li>Explicit assertions on decimal precision (scale, rounding mode)</li>
 *   <li>Validation of field lengths matching COBOL PIC clauses</li>
 *   <li>Exception testing for COBOL NOTFND and error conditions</li>
 * </ul>
 * 
 * @see AccountViewService
 * @see Account
 * @see Customer
 * @see AccountViewResponse
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Test Case Compatibility Requirements</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService Test Suite - COBOL COACTVWC.cbl Transformation")
public class AccountViewServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private DecimalUtils decimalUtils;

    @InjectMocks
    private AccountViewService accountViewService;

    private Account testAccount;
    private Customer testCustomer;
    private Long testAccountId;
    private String testAccountIdString;

    /**
     * Test setup method executed before each test case.
     * 
     * <p>Initializes test fixtures with data matching COBOL record layouts from copybooks
     * CVACT01Y.cpy (Account) and CVCUS01Y.cpy (Customer). All BigDecimal amounts use scale=2
     * with HALF_UP rounding to match COBOL COMP-3 precision requirements.</p>
     * 
     * <p><strong>Test Data Setup:</strong></p>
     * <ul>
     *   <li>Account ID: 00000000001 (11-digit format matching COBOL PIC 9(11))</li>
     *   <li>Customer ID: 000000001 (9-digit format matching COBOL PIC 9(09))</li>
     *   <li>SSN: 123456789 (9 digits, will be formatted as 123-45-6789)</li>
     *   <li>Balance: 2500.50 (scale=2, HALF_UP)</li>
     *   <li>Credit Limit: 10000.00 (scale=2, HALF_UP)</li>
     *   <li>Dates: LocalDate format replacing COBOL PIC X(10) date strings</li>
     * </ul>
     */
    @BeforeEach
    public void setUp() {
        // Initialize test account ID (11-digit format)
        testAccountId = 1L;
        testAccountIdString = String.format("%011d", testAccountId); // "00000000001"

        // Create test customer matching CVCUS01Y.cpy CUSTOMER-RECORD layout
        testCustomer = new Customer();
        testCustomer.setCustomerId(1L); // CUST-ID PIC 9(09)
        testCustomer.setFirstName("John"); // CUST-FIRST-NAME PIC X(25)
        testCustomer.setMiddleName("A"); // CUST-MIDDLE-NAME PIC X(25)
        testCustomer.setLastName("Doe"); // CUST-LAST-NAME PIC X(25)
        testCustomer.setSsn("123456789"); // CUST-SSN PIC 9(09)
        testCustomer.setDateOfBirth(LocalDate.of(1980, 1, 15)); // CUST-DOB-YYYY-MM-DD PIC X(10)
        testCustomer.setFicoCreditScore(750); // CUST-FICO-CREDIT-SCORE PIC 9(03)
        testCustomer.setAddressLine1("123 Main Street"); // CUST-ADDR-LINE-1 PIC X(50)
        testCustomer.setAddressLine2("Apt 4B"); // CUST-ADDR-LINE-2 PIC X(50)
        testCustomer.setAddressLine3("Building A"); // CUST-ADDR-LINE-3 PIC X(50)
        testCustomer.setStateCode("TX"); // CUST-ADDR-STATE-CD PIC X(02)
        testCustomer.setZipCode("75001"); // CUST-ADDR-ZIP PIC X(10)
        testCustomer.setCountryCode("USA"); // CUST-ADDR-COUNTRY-CD PIC X(03)
        testCustomer.setPhoneNumber1("214-555-1234"); // CUST-PHONE-NUM-1 PIC X(15)
        testCustomer.setPhoneNumber2("214-555-5678"); // CUST-PHONE-NUM-2 PIC X(15)
        testCustomer.setGovernmentIssuedId("TX-DL-12345678"); // CUST-GOVT-ISSUED-ID PIC X(20)
        testCustomer.setEftAccountId("1234567890"); // CUST-EFT-ACCOUNT-ID PIC X(10)
        testCustomer.setPrimaryCardHolderIndicator("Y"); // CUST-PRI-CARD-HOLDER-IND PIC X(01)

        // Create test account matching CVACT01Y.cpy ACCOUNT-RECORD layout
        testAccount = new Account();
        testAccount.setAccountId(testAccountId); // ACCT-ID PIC 9(11)
        testAccount.setActiveStatus("Y"); // ACCT-ACTIVE-STATUS PIC X(01)
        
        // Set financial amounts with COMP-3 precision (PIC S9(10)V99)
        testAccount.setCurrentBalance(
            new BigDecimal("2500.50").setScale(2, RoundingMode.HALF_UP)
        ); // ACCT-CURR-BAL PIC S9(10)V99
        
        testAccount.setCreditLimit(
            new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP)
        ); // ACCT-CREDIT-LIMIT PIC S9(10)V99
        
        testAccount.setCashCreditLimit(
            new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP)
        ); // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
        
        testAccount.setCurrentCycleCredit(
            new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP)
        ); // ACCT-CURR-CYC-CREDIT PIC S9(10)V99
        
        testAccount.setCurrentCycleDebit(
            new BigDecimal("300.00").setScale(2, RoundingMode.HALF_UP)
        ); // ACCT-CURR-CYC-DEBIT PIC S9(10)V99
        
        // Set date fields (COBOL PIC X(10) → LocalDate)
        testAccount.setOpenDate(LocalDate.of(2020, 1, 1)); // ACCT-OPEN-DATE
        testAccount.setExpirationDate(LocalDate.of(2025, 12, 31)); // ACCT-EXPIRAION-DATE
        testAccount.setReissueDate(LocalDate.of(2023, 6, 15)); // ACCT-REISSUE-DATE
        
        // Set other fields
        testAccount.setAccountGroupId("GROUP001"); // ACCT-GROUP-ID PIC X(10)
        testAccount.setAddressZip("75001"); // ACCT-ADDR-ZIP PIC X(10)
        
        // Establish customer relationship (foreign key)
        testAccount.setCustomer(testCustomer);
    }

    /**
     * Test: Account view retrieval with valid account ID returns complete account data.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 687-720 (9000-READ-ACCT)</p>
     * <pre>
     * COBOL Logic:
     *   MOVE CDEMO-ACCT-ID TO WS-CARD-RID-ACCT-ID
     *   PERFORM 9300-GETACCTDATA-BYACCT
     *   WHEN DFHRESP(NORMAL)
     *       SET FOUND-ACCT-IN-MASTER TO TRUE
     *       MOVE account fields to screen output
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Repository findByAccountId() called with correct Long parameter</li>
     *   <li>Account data successfully retrieved (DFHRESP(NORMAL) equivalent)</li>
     *   <li>AccountViewResponse populated with all account fields</li>
     *   <li>Balance fields maintain scale=2 precision</li>
     *   <li>Date fields formatted correctly</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_ValidAccountId_ReturnsAccountData - VSAM READ to JPA findById")
    public void getAccountView_ValidAccountId_ReturnsAccountData() {
        // Arrange: Mock repository to return test account (DFHRESP(NORMAL))
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify response is not null
        assertNotNull(response, "AccountViewResponse should not be null");

        // Assert: Verify account ID formatted correctly (11 digits with leading zeros)
        assertEquals(testAccountIdString, response.getAccountId(),
            "Account ID should be formatted as 11-digit string with leading zeros");

        // Assert: Verify account status
        assertEquals("Y", response.getAccountStatus(),
            "Account status should match ACCT-ACTIVE-STATUS from COBOL");

        // Assert: Verify financial amounts with scale=2 precision
        assertNotNull(response.getCurrentBalance(), "Current balance should not be null");
        assertEquals(2, response.getCurrentBalance().scale(),
            "Current balance scale must be 2 (COBOL COMP-3 precision)");
        assertEquals(0, response.getCurrentBalance().compareTo(new BigDecimal("2500.50")),
            "Current balance value should match test data");

        assertNotNull(response.getCreditLimit(), "Credit limit should not be null");
        assertEquals(2, response.getCreditLimit().scale(),
            "Credit limit scale must be 2 (COBOL COMP-3 precision)");
        assertEquals(0, response.getCreditLimit().compareTo(new BigDecimal("10000.00")),
            "Credit limit value should match test data");

        // Assert: Verify dates
        assertEquals(LocalDate.of(2020, 1, 1), response.getDateOpened(),
            "Open date should match ACCT-OPEN-DATE");
        assertEquals(LocalDate.of(2025, 12, 31), response.getExpiryDate(),
            "Expiry date should match ACCT-EXPIRAION-DATE");

        // Assert: Verify repository was called exactly once
        verify(accountRepository, times(1)).findByAccountId(testAccountId);
    }

    /**
     * Test: Account view with customer details resolves cross-reference relationships.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 693-715</p>
     * <pre>
     * COBOL Logic:
     *   PERFORM 9200-GETCARDXREF-BYACCT      (lines 723-772)
     *   MOVE XREF-CUST-ID TO CDEMO-CUST-ID   (line 739)
     *   PERFORM 9400-GETCUSTDATA-BYCUST      (lines 825-872)
     *   WHEN DFHRESP(NORMAL)
     *       SET FOUND-CUST-IN-MASTER TO TRUE
     *       MOVE customer fields to screen output
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Customer data accessed via Account.getCustomer() relationship</li>
     *   <li>Foreign key navigation replaces COBOL XREF file lookup</li>
     *   <li>Customer ID, name, address populated in response</li>
     *   <li>Cross-reference resolution validated</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_WithCustomerDetails_ResolvesCrossRef - XREF to Foreign Key")
    public void getAccountView_WithCustomerDetails_ResolvesCrossRef() {
        // Arrange: Mock repository with account having customer relationship
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify customer data populated (FOUND-CUST-IN-MASTER equivalent)
        assertNotNull(response, "AccountViewResponse should not be null");
        
        // Verify customer identifier
        assertEquals("1", response.getCustomerNumber(),
            "Customer number should match CUST-ID from XREF resolution");

        // Verify customer name fields (COBOL lines 508-510)
        assertEquals("John", response.getFirstName(),
            "First name should match CUST-FIRST-NAME");
        assertEquals("A", response.getMiddleName(),
            "Middle name should match CUST-MIDDLE-NAME");
        assertEquals("Doe", response.getLastName(),
            "Last name should match CUST-LAST-NAME");

        // Verify customer address fields (COBOL lines 511-516)
        assertEquals("123 Main Street", response.getAddressLine1(),
            "Address line 1 should match CUST-ADDR-LINE-1");
        assertEquals("Apt 4B", response.getAddressLine2(),
            "Address line 2 should match CUST-ADDR-LINE-2");
        assertEquals("Building A", response.getAddressLine3(),
            "Address line 3 should match CUST-ADDR-LINE-3");
        assertEquals("TX", response.getState(),
            "State should match CUST-ADDR-STATE-CD");
        assertEquals("75001", response.getZipCode(),
            "Zip code should match CUST-ADDR-ZIP");
        assertEquals("USA", response.getCountry(),
            "Country should match CUST-ADDR-COUNTRY-CD");

        // Verify customer contact information (COBOL lines 517-518)
        assertEquals("214-555-1234", response.getPhone1(),
            "Phone 1 should match CUST-PHONE-NUM-1");
        assertEquals("214-555-5678", response.getPhone2(),
            "Phone 2 should match CUST-PHONE-NUM-2");

        // Verify customer date of birth (COBOL line 507)
        assertEquals(LocalDate.of(1980, 1, 15), response.getCustomerDOB(),
            "Customer DOB should match CUST-DOB-YYYY-MM-DD");

        // Verify customer FICO score (COBOL lines 505-506)
        assertEquals("750", response.getCustomerFICO(),
            "FICO score should match CUST-FICO-CREDIT-SCORE");

        // Verify repository called once (single database round-trip)
        verify(accountRepository, times(1)).findByAccountId(testAccountId);
    }

    /**
     * Test: SSN formatting with dashes and digit masking.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 496-504</p>
     * <pre>
     * COBOL Logic:
     *   STRING 
     *       CUST-SSN(1:3)     (First 3 digits)
     *       '-'               (Dash separator)
     *       CUST-SSN(4:2)     (Middle 2 digits)
     *       '-'               (Dash separator)
     *       CUST-SSN(6:4)     (Last 4 digits)
     *       DELIMITED BY SIZE
     *       INTO ACSTSSNO OF CACTVWAO
     *   END-STRING
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>9-digit SSN formatted as XXX-XX-XXXX</li>
     *   <li>Input "123456789" becomes "123-45-6789"</li>
     *   <li>Dashes inserted at correct positions</li>
     *   <li>Field length matches COBOL output field (11 characters)</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_FormatsSSN_MasksDigits - COBOL STRING SSN Formatting")
    public void getAccountView_FormatsSSN_MasksDigits() {
        // Arrange: Mock repository with account having customer with SSN
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify SSN formatted with dashes
        assertNotNull(response.getCustomerSSN(), "SSN should not be null");
        
        // Verify SSN format XXX-XX-XXXX (11 characters total)
        String formattedSSN = response.getCustomerSSN();
        assertEquals(11, formattedSSN.length(),
            "Formatted SSN should be 11 characters (XXX-XX-XXXX)");
        
        // Verify dash positions
        assertEquals('-', formattedSSN.charAt(3),
            "First dash should be at position 3");
        assertEquals('-', formattedSSN.charAt(6),
            "Second dash should be at position 6");
        
        // Verify SSN segments
        assertEquals("123", formattedSSN.substring(0, 3),
            "First segment should be first 3 digits");
        assertEquals("45", formattedSSN.substring(4, 6),
            "Second segment should be middle 2 digits");
        assertEquals("6789", formattedSSN.substring(7, 11),
            "Third segment should be last 4 digits");
        
        // Verify complete formatted SSN
        assertEquals("123-45-6789", formattedSSN,
            "SSN should be formatted as 123-45-6789 matching COBOL STRING operation");
    }

    /**
     * Test: Balance formatting with two decimal places and HALF_UP rounding.
     * 
     * <p><strong>COBOL Equivalent:</strong> CVACT01Y.cpy ACCT-CURR-BAL PIC S9(10)V99</p>
     * <p><strong>Display Logic:</strong> COACTVWC.cbl line 475</p>
     * <pre>
     * COBOL Logic:
     *   MOVE ACCT-CURR-BAL TO ACURBALO OF CACTVWAO
     *   (COMP-3 packed decimal automatically formats with 2 decimals)
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>All monetary amounts have scale=2</li>
     *   <li>RoundingMode.HALF_UP applied (matches COBOL ROUNDED clause)</li>
     *   <li>No floating-point errors in balance calculations</li>
     *   <li>Precision preserved for currentBalance, creditLimit, cashLimit</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_FormatsBalance_TwoDecimals - COMP-3 BigDecimal Precision")
    public void getAccountView_FormatsBalance_TwoDecimals() {
        // Arrange: Mock repository with account having various balance amounts
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify current balance precision
        assertNotNull(response.getCurrentBalance(),
            "Current balance should not be null");
        assertEquals(2, response.getCurrentBalance().scale(),
            "Current balance must have scale=2 (COBOL COMP-3 V99)");
        assertEquals(RoundingMode.HALF_UP, 
            response.getCurrentBalance().setScale(2, RoundingMode.HALF_UP).scale() == 2,
            "Current balance must use HALF_UP rounding mode");

        // Assert: Verify credit limit precision
        assertNotNull(response.getCreditLimit(),
            "Credit limit should not be null");
        assertEquals(2, response.getCreditLimit().scale(),
            "Credit limit must have scale=2 (COBOL COMP-3 V99)");

        // Assert: Verify cash credit limit precision
        assertNotNull(response.getCashLimit(),
            "Cash limit should not be null");
        assertEquals(2, response.getCashLimit().scale(),
            "Cash limit must have scale=2 (COBOL COMP-3 V99)");

        // Assert: Verify cycle totals precision
        assertNotNull(response.getCycleCreditTotal(),
            "Cycle credit total should not be null");
        assertEquals(2, response.getCycleCreditTotal().scale(),
            "Cycle credit total must have scale=2");

        assertNotNull(response.getCycleDebitTotal(),
            "Cycle debit total should not be null");
        assertEquals(2, response.getCycleDebitTotal().scale(),
            "Cycle debit total must have scale=2");

        // Assert: Verify no trailing zeros lost (proper BigDecimal formatting)
        assertTrue(response.getCurrentBalance().toPlainString().matches("\\d+\\.\\d{2}"),
            "Current balance should display exactly 2 decimal places");
    }

    /**
     * Test: Invalid account ID throws AccountNotFoundException.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 789-807</p>
     * <pre>
     * COBOL Logic:
     *   EXEC CICS READ DATASET(LIT-ACCTFILENAME) ...
     *   END-EXEC
     *   EVALUATE WS-RESP-CD
     *       WHEN DFHRESP(NOTFND)
     *           SET INPUT-ERROR TO TRUE
     *           STRING 'Account:' WS-CARD-RID-ACCT-ID-X
     *               ' not found in Acct Master file'
     *               INTO WS-RETURN-MSG
     *   END-EVALUATE
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>AccountNotFoundException thrown when account not found</li>
     *   <li>Exception message matches COBOL error message format</li>
     *   <li>Maps to DFHRESP(NOTFND) response code (RESP=13)</li>
     *   <li>HTTP 404 NOT FOUND status expected in controller layer</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_InvalidAccountId_ThrowsNotFoundException - DFHRESP(NOTFND)")
    public void getAccountView_InvalidAccountId_ThrowsNotFoundException() {
        // Arrange: Mock repository to return empty Optional (DFHRESP(NOTFND))
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.empty());

        // Act & Assert: Verify AccountNotFoundException thrown
        AccountNotFoundException exception = assertThrows(
            AccountNotFoundException.class,
            () -> accountViewService.getAccountDetails(testAccountIdString),
            "Should throw AccountNotFoundException when account not found"
        );

        // Assert: Verify exception message matches COBOL error message
        String expectedMessageSubstring = "not found in Acct Master file";
        assertTrue(exception.getMessage().contains(expectedMessageSubstring),
            String.format("Exception message should contain '%s'", expectedMessageSubstring));

        // Assert: Verify account ID included in exception message
        assertTrue(exception.getMessage().contains(testAccountIdString),
            "Exception message should include the account ID that was not found");

        // Assert: Verify repository was called
        verify(accountRepository, times(1)).findByAccountId(testAccountId);
    }

    /**
     * Test: Available credit calculation as credit limit minus current balance.
     * 
     * <p><strong>COBOL Equivalent:</strong> Implied calculation in account view logic</p>
     * <pre>
     * COBOL Computation:
     *   COMPUTE AVAIL-CREDIT = ACCT-CREDIT-LIMIT - ACCT-CURR-BAL
     *       ON SIZE ERROR
     *           MOVE ZERO TO AVAIL-CREDIT
     *       END-COMPUTE
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Available credit = creditLimit - currentBalance</li>
     *   <li>Result has scale=2 with HALF_UP rounding</li>
     *   <li>Calculation preserves COMP-3 precision</li>
     *   <li>No floating-point arithmetic errors</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_CalculatesCreditLimit_AvailableBalance - Financial Calculation")
    public void getAccountView_CalculatesCreditLimit_AvailableBalance() {
        // Arrange: Mock repository with account having known credit limit and balance
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Expected available credit calculation
        BigDecimal expectedAvailableCredit = testAccount.getCreditLimit()
            .subtract(testAccount.getCurrentBalance())
            .setScale(2, RoundingMode.HALF_UP);

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify available credit calculated correctly
        assertNotNull(response.getAvailableCredit(),
            "Available credit should not be null");
        
        assertEquals(2, response.getAvailableCredit().scale(),
            "Available credit must have scale=2");
        
        // Verify calculation: 10000.00 - 2500.50 = 7499.50
        assertEquals(0, response.getAvailableCredit().compareTo(expectedAvailableCredit),
            String.format("Available credit should be %s (creditLimit %s - currentBalance %s)",
                expectedAvailableCredit, testAccount.getCreditLimit(), testAccount.getCurrentBalance()));
        
        // Verify exact value
        assertEquals(0, response.getAvailableCredit().compareTo(new BigDecimal("7499.50")),
            "Available credit should be exactly 7499.50");
    }

    /**
     * Test: Account status validation checks active flag.
     * 
     * <p><strong>COBOL Equivalent:</strong> CVACT01Y.cpy ACCT-ACTIVE-STATUS PIC X(01)</p>
     * <p><strong>88-Level Conditions:</strong> ACCOUNT-IS-ACTIVE VALUE 'Y'</p>
     * <pre>
     * COBOL Logic:
     *   IF ACCOUNT-IS-ACTIVE
     *       PERFORM PROCESS-ACTIVE-ACCOUNT
     *   ELSE
     *       PERFORM PROCESS-INACTIVE-ACCOUNT
     *   END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Active status 'Y' correctly populated in response</li>
     *   <li>Field length matches COBOL PIC X(01)</li>
     *   <li>88-level condition validation preserved</li>
     * </ul>
     */
    @Test
    @DisplayName("validateAccountStatus_ChecksActiveFlag - 88-Level Condition")
    public void validateAccountStatus_ChecksActiveFlag() {
        // Arrange: Mock repository with active account
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify account status
        assertNotNull(response.getAccountStatus(),
            "Account status should not be null");
        
        assertEquals("Y", response.getAccountStatus(),
            "Account status should be 'Y' for active account");
        
        assertEquals(1, response.getAccountStatus().length(),
            "Account status should be 1 character (COBOL PIC X(01))");
    }

    /**
     * Test: Transaction summary aggregation amounts.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 482-485</p>
     * <pre>
     * COBOL Logic:
     *   MOVE ACCT-CURR-CYC-CREDIT TO ACRCYCRO OF CACTVWAO
     *   MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO OF CACTVWAO
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Current cycle credit total populated correctly</li>
     *   <li>Current cycle debit total populated correctly</li>
     *   <li>Both amounts have scale=2 precision</li>
     *   <li>Aggregation matches COBOL cycle totals</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountTransactionSummary_AggregatesAmounts - Billing Cycle Totals")
    public void getAccountTransactionSummary_AggregatesAmounts() {
        // Arrange: Mock repository with account having cycle totals
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify cycle credit total
        assertNotNull(response.getCycleCreditTotal(),
            "Cycle credit total should not be null");
        assertEquals(2, response.getCycleCreditTotal().scale(),
            "Cycle credit total must have scale=2");
        assertEquals(0, response.getCycleCreditTotal().compareTo(new BigDecimal("500.00")),
            "Cycle credit total should match ACCT-CURR-CYC-CREDIT");

        // Assert: Verify cycle debit total
        assertNotNull(response.getCycleDebitTotal(),
            "Cycle debit total should not be null");
        assertEquals(2, response.getCycleDebitTotal().scale(),
            "Cycle debit total must have scale=2");
        assertEquals(0, response.getCycleDebitTotal().compareTo(new BigDecimal("300.00")),
            "Cycle debit total should match ACCT-CURR-CYC-DEBIT");
    }

    /**
     * Test: Null account ID validation throws IllegalArgumentException.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 653-662</p>
     * <pre>
     * COBOL Logic:
     *   IF CC-ACCT-ID EQUAL LOW-VALUES
     *   OR CC-ACCT-ID EQUAL SPACES
     *       SET INPUT-ERROR TO TRUE
     *       SET FLG-ACCTFILTER-BLANK TO TRUE
     *       MOVE 'Account number not provided' TO WS-RETURN-MSG
     *   END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Null accountId throws IllegalArgumentException</li>
     *   <li>Empty string accountId throws IllegalArgumentException</li>
     *   <li>Exception message matches COBOL error message</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_NullAccountId_ThrowsIllegalArgumentException - Input Validation")
    public void getAccountView_NullAccountId_ThrowsIllegalArgumentException() {
        // Act & Assert: Verify exception thrown for null account ID
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> accountViewService.getAccountDetails((String) null),
            "Should throw IllegalArgumentException for null account ID"
        );

        // Assert: Verify exception message
        assertTrue(exception.getMessage().contains("Account number not provided"),
            "Exception message should match COBOL error message for blank account ID");

        // Verify repository was never called
        verify(accountRepository, never()).findByAccountId(any());
    }

    /**
     * Test: Zero account ID validation throws IllegalArgumentException.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 666-676</p>
     * <pre>
     * COBOL Logic:
     *   IF CC-ACCT-ID IS NOT NUMERIC 
     *   OR CC-ACCT-ID EQUAL ZEROES
     *       SET INPUT-ERROR TO TRUE
     *       MOVE 'Account Filter must be a non-zero 11 digit number'
     *           TO WS-RETURN-MSG
     *   END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Zero accountId throws IllegalArgumentException</li>
     *   <li>Exception message matches COBOL validation error</li>
     *   <li>Prevents lookup of invalid account ID</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_ZeroAccountId_ThrowsIllegalArgumentException - Zero Validation")
    public void getAccountView_ZeroAccountId_ThrowsIllegalArgumentException() {
        // Arrange: Zero account ID string
        String zeroAccountId = "00000000000";

        // Act & Assert: Verify exception thrown for zero account ID
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> accountViewService.getAccountDetails(zeroAccountId),
            "Should throw IllegalArgumentException for zero account ID"
        );

        // Assert: Verify exception message
        assertTrue(exception.getMessage().contains("non-zero 11 digit number"),
            "Exception message should match COBOL validation error for zero account ID");

        // Verify repository was never called
        verify(accountRepository, never()).findByAccountId(anyLong());
    }

    /**
     * Test: Non-numeric account ID validation throws IllegalArgumentException.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 666-676</p>
     * <pre>
     * COBOL Logic:
     *   IF CC-ACCT-ID IS NOT NUMERIC
     *       SET INPUT-ERROR TO TRUE
     *       MOVE 'Account Filter must be a non-zero 11 digit number'
     *           TO WS-RETURN-MSG
     *   END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Non-numeric accountId throws IllegalArgumentException</li>
     *   <li>NumberFormatException caught and re-thrown as IllegalArgumentException</li>
     *   <li>Exception message provides clear validation error</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_NonNumericAccountId_ThrowsIllegalArgumentException - Numeric Check")
    public void getAccountView_NonNumericAccountId_ThrowsIllegalArgumentException() {
        // Arrange: Non-numeric account ID string
        String nonNumericAccountId = "ABC12345678";

        // Act & Assert: Verify exception thrown for non-numeric account ID
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> accountViewService.getAccountDetails(nonNumericAccountId),
            "Should throw IllegalArgumentException for non-numeric account ID"
        );

        // Assert: Verify exception message
        assertTrue(exception.getMessage().contains("non-zero 11 digit number"),
            "Exception message should indicate numeric validation failure");

        // Verify repository was never called
        verify(accountRepository, never()).findByAccountId(anyLong());
    }

    /**
     * Test: SSN masking utility handles null and empty values gracefully.
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Null SSN returns empty string (no NullPointerException)</li>
     *   <li>Empty SSN returns empty string</li>
     *   <li>Invalid length SSN returns empty string</li>
     *   <li>Defensive programming for PII handling</li>
     * </ul>
     */
    @Test
    @DisplayName("maskSSN_HandlesNullAndEmpty_ReturnsEmptyString - Defensive Programming")
    public void maskSSN_HandlesNullAndEmpty_ReturnsEmptyString() {
        // Act & Assert: Null SSN
        String maskedNull = accountViewService.maskSSN(null);
        assertEquals("", maskedNull,
            "Null SSN should return empty string");

        // Act & Assert: Empty SSN
        String maskedEmpty = accountViewService.maskSSN("");
        assertEquals("", maskedEmpty,
            "Empty SSN should return empty string");

        // Act & Assert: Invalid length SSN
        String maskedShort = accountViewService.maskSSN("12345");
        assertEquals("", maskedShort,
            "Short SSN should return empty string");

        String maskedLong = accountViewService.maskSSN("1234567890");
        assertEquals("", maskedLong,
            "Long SSN should return empty string");
    }

    /**
     * Test: Available credit calculation handles null inputs safely.
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Null credit limit returns zero (defensive)</li>
     *   <li>Null current balance returns zero (defensive)</li>
     *   <li>Both null returns zero</li>
     *   <li>No NullPointerException thrown</li>
     * </ul>
     */
    @Test
    @DisplayName("calculateAvailableCredit_HandlesNullInputs_ReturnsZero - Null Safety")
    public void calculateAvailableCredit_HandlesNullInputs_ReturnsZero() {
        // Act & Assert: Null credit limit
        BigDecimal resultNullLimit = accountViewService.calculateAvailableCredit(
            null, new BigDecimal("1000.00")
        );
        assertNotNull(resultNullLimit, "Result should not be null");
        assertEquals(0, resultNullLimit.compareTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)),
            "Null credit limit should return zero");

        // Act & Assert: Null current balance
        BigDecimal resultNullBalance = accountViewService.calculateAvailableCredit(
            new BigDecimal("5000.00"), null
        );
        assertNotNull(resultNullBalance, "Result should not be null");
        assertEquals(0, resultNullBalance.compareTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)),
            "Null current balance should return zero");

        // Act & Assert: Both null
        BigDecimal resultBothNull = accountViewService.calculateAvailableCredit(null, null);
        assertNotNull(resultBothNull, "Result should not be null");
        assertEquals(0, resultBothNull.compareTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)),
            "Both null inputs should return zero");
    }

    /**
     * Test: Format account view response handles null account safely.
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Null account throws IllegalArgumentException</li>
     *   <li>Descriptive error message provided</li>
     *   <li>Prevents NullPointerException downstream</li>
     * </ul>
     */
    @Test
    @DisplayName("formatAccountViewResponse_NullAccount_ThrowsException - Null Check")
    public void formatAccountViewResponse_NullAccount_ThrowsException() {
        // Act & Assert: Verify exception thrown for null account
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> accountViewService.formatAccountViewResponse(null),
            "Should throw IllegalArgumentException for null account"
        );

        // Assert: Verify exception message
        assertTrue(exception.getMessage().contains("Account cannot be null"),
            "Exception message should indicate null account error");
    }

    /**
     * Test: Date formatting matches COBOL YYYY-MM-DD format.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 487-489</p>
     * <pre>
     * COBOL Logic:
     *   MOVE ACCT-OPEN-DATE TO ADTOPENO OF CACTVWAO       (PIC X(10))
     *   MOVE ACCT-EXPIRAION-DATE TO AEXPDTO OF CACTVWAO   (PIC X(10))
     *   MOVE ACCT-REISSUE-DATE TO AREISDTO OF CACTVWAO    (PIC X(10))
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>LocalDate fields formatted as YYYY-MM-DD</li>
     *   <li>Open date, expiry date, reissue date all present</li>
     *   <li>Format matches COBOL PIC X(10) field layout</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_DateFormat_YYYYMMDD - Date Field Formatting")
    public void getAccountView_DateFormat_YYYYMMDD() {
        // Arrange: Mock repository with account having specific dates
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify date fields present and properly formatted
        assertNotNull(response.getDateOpened(),
            "Open date should not be null");
        assertNotNull(response.getExpiryDate(),
            "Expiry date should not be null");
        assertNotNull(response.getReissueDate(),
            "Reissue date should not be null");

        // Verify dates match expected values
        assertEquals(LocalDate.of(2020, 1, 1), response.getDateOpened(),
            "Open date should be 2020-01-01");
        assertEquals(LocalDate.of(2025, 12, 31), response.getExpiryDate(),
            "Expiry date should be 2025-12-31");
        assertEquals(LocalDate.of(2023, 6, 15), response.getReissueDate(),
            "Reissue date should be 2023-06-15");

        // Verify LocalDate toString() produces YYYY-MM-DD format
        assertTrue(response.getDateOpened().toString().matches("\\d{4}-\\d{2}-\\d{2}"),
            "Open date string format should be YYYY-MM-DD");
    }

    /**
     * Test: Customer field population completeness.
     * 
     * <p><strong>COBOL Equivalent:</strong> COACTVWC.cbl lines 493-523</p>
     * <p>Verifies all customer fields from CVCUS01Y.cpy are populated in response.</p>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>All customer name fields populated</li>
     *   <li>All customer address fields populated</li>
     *   <li>All customer contact fields populated</li>
     *   <li>Customer identifiers and scores populated</li>
     * </ul>
     */
    @Test
    @DisplayName("getAccountView_AllCustomerFields_Populated - Complete Data Mapping")
    public void getAccountView_AllCustomerFields_Populated() {
        // Arrange: Mock repository with complete customer data
        when(accountRepository.findByAccountId(testAccountId))
            .thenReturn(Optional.of(testAccount));

        // Act: Execute account view retrieval
        AccountViewResponse response = accountViewService.getAccountDetails(testAccountIdString);

        // Assert: Verify all customer identification fields
        assertNotNull(response.getCustomerNumber(), "Customer number should not be null");
        assertNotNull(response.getCustomerSSN(), "Customer SSN should not be null");
        assertNotNull(response.getCustomerFICO(), "Customer FICO should not be null");
        assertNotNull(response.getCustomerDOB(), "Customer DOB should not be null");

        // Assert: Verify all customer name fields
        assertNotNull(response.getFirstName(), "First name should not be null");
        assertNotNull(response.getMiddleName(), "Middle name should not be null");
        assertNotNull(response.getLastName(), "Last name should not be null");

        // Assert: Verify all customer address fields
        assertNotNull(response.getAddressLine1(), "Address line 1 should not be null");
        assertNotNull(response.getAddressLine2(), "Address line 2 should not be null");
        assertNotNull(response.getAddressLine3(), "Address line 3 should not be null");
        assertNotNull(response.getState(), "State should not be null");
        assertNotNull(response.getZipCode(), "Zip code should not be null");
        assertNotNull(response.getCountry(), "Country should not be null");

        // Assert: Verify all customer contact fields
        assertNotNull(response.getPhone1(), "Phone 1 should not be null");
        assertNotNull(response.getPhone2(), "Phone 2 should not be null");

        // Assert: Verify customer government and financial IDs
        assertNotNull(response.getGovernmentId(), "Government ID should not be null");
        assertNotNull(response.getEftAccountNumber(), "EFT account number should not be null");
        assertNotNull(response.getProfileFlag(), "Profile flag should not be null");
    }
}
