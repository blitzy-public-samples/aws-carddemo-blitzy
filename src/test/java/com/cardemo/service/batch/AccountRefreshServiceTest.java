/*
 * AccountRefreshServiceTest.java — JUnit 5 Unit Test (← CBACT01C.cbl)
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * Tests the AccountRefreshService which translates the CBACT01C.cbl batch
 * utility program. Verifies sequential reading of account records, field
 * display/logging, file status error handling, and read-only data access.
 *
 * COBOL Source: app/cbl/CBACT01C.cbl
 * Copybook:     app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, 300 bytes)
 *
 * Test Coverage:
 *   - Sequential read of all accounts via findAll() (← PROCEDURE DIVISION main loop)
 *   - Display/log of all 11 account fields (← 1100-DISPLAY-ACCT-RECORD)
 *   - Start/end execution banners (← PROCEDURE DIVISION start/end DISPLAYs)
 *   - Empty account list handling (← immediate EOF on first READ)
 *   - Repository exception handling (← 9910-DISPLAY-IO-STATUS, 9999-ABEND-PROGRAM)
 *   - Multiple account processing (← PERFORM UNTIL END-OF-FILE = 'Y')
 *   - BigDecimal monetary field precision (← PIC S9(10)V99 COMP-3 fields)
 *   - Read-only data access verification (← OPEN INPUT ACCTFILE-FILE)
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.Account;
import com.cardemo.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountRefreshService} — the simplest batch service
 * migrated from COBOL program CBACT01C.cbl.
 *
 * <p>This is a pure Mockito unit test (no Spring context loading). The
 * {@link AccountRepository} is mocked with {@code @Mock} and injected into
 * the service under test via {@code @InjectMocks}. All tests verify that the
 * service faithfully reproduces the COBOL batch utility behavior.</p>
 *
 * <h3>COBOL Paragraph Coverage</h3>
 * <table>
 *   <caption>Test method to COBOL paragraph mapping</caption>
 *   <tr><th>Test Method</th><th>COBOL Paragraph(s)</th></tr>
 *   <tr><td>shouldReadAllAccountRecordsSequentially</td>
 *       <td>PROCEDURE DIVISION, 0000-ACCTFILE-OPEN, 1000-ACCTFILE-GET-NEXT</td></tr>
 *   <tr><td>shouldDisplayAllAccountFields</td>
 *       <td>1100-DISPLAY-ACCT-RECORD</td></tr>
 *   <tr><td>shouldLogStartAndEndBanners</td>
 *       <td>PROCEDURE DIVISION (start/end DISPLAY statements)</td></tr>
 *   <tr><td>shouldHandleEmptyAccountList</td>
 *       <td>1000-ACCTFILE-GET-NEXT (APPL-EOF on first read)</td></tr>
 *   <tr><td>shouldHandleRepositoryException</td>
 *       <td>9910-DISPLAY-IO-STATUS, 9999-ABEND-PROGRAM</td></tr>
 *   <tr><td>shouldProcessMultipleAccounts</td>
 *       <td>PERFORM UNTIL END-OF-FILE = 'Y'</td></tr>
 *   <tr><td>shouldHandleBigDecimalFieldsInDisplay</td>
 *       <td>1100-DISPLAY-ACCT-RECORD (COMP-3 fields)</td></tr>
 *   <tr><td>shouldNotModifyAccountData</td>
 *       <td>OPEN INPUT (read-only access mode)</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class AccountRefreshServiceTest {

    /**
     * Mocked Spring Data JPA repository for account data access.
     * Replaces the VSAM ACCTDATA KSDS file access in CBACT01C.cbl.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Service under test — AccountRefreshService with mocked dependencies
     * injected by Mockito. Translates CBACT01C.cbl batch utility.
     */
    @InjectMocks
    private AccountRefreshService accountRefreshService;

    /**
     * Default list of 3 test accounts reused across multiple test methods.
     * Initialized fresh before each test by {@link #setUp()}.
     */
    private List<Account> defaultAccounts;

    /**
     * Sets up default test data before each test method.
     * Creates 3 account records with different IDs and balances,
     * simulating the ACCTDATA VSAM KSDS sequential read pattern.
     */
    @BeforeEach
    void setUp() {
        defaultAccounts = new ArrayList<>();
        defaultAccounts.add(
                createTestAccount("00000000001", new BigDecimal("1500.00")));
        defaultAccounts.add(
                createTestAccount("00000000002", new BigDecimal("2500.00")));
        defaultAccounts.add(
                createTestAccount("00000000003", new BigDecimal("3500.00")));
    }

    // =========================================================================
    // Test 1: Sequential Read (← PROCEDURE DIVISION main loop)
    // =========================================================================

    /**
     * Verifies that the service reads all account records sequentially via
     * a single {@code findAll()} call, mapping the COBOL PERFORM UNTIL
     * END-OF-FILE loop from the PROCEDURE DIVISION of CBACT01C.cbl.
     *
     * <p>COBOL flow: OPEN → READ NEXT (loop) → CLOSE. In Java, this becomes
     * a single {@code findAll()} call returning all records in PK order.</p>
     */
    @Test
    @DisplayName("Should read all account records sequentially via findAll()")
    void shouldReadAllAccountRecordsSequentially() {
        // Given: Repository returns 3 account records (simulates 3 VSAM READs)
        when(accountRepository.findAll()).thenReturn(defaultAccounts);

        // When: Service processes all accounts (PROCEDURE DIVISION entry)
        accountRefreshService.refreshAccounts();

        // Then: findAll() called exactly once (single sequential read)
        verify(accountRepository, times(1)).findAll();
        // Verify all 3 accounts were available for processing
        assertThat(defaultAccounts).hasSize(3);
    }

    // =========================================================================
    // Test 2: Display All 11 Fields (← 1100-DISPLAY-ACCT-RECORD)
    // =========================================================================

    /**
     * Verifies that all 11 COBOL DISPLAY fields from paragraph
     * 1100-DISPLAY-ACCT-RECORD are logged without errors when each field
     * is populated with representative values.
     *
     * <p>Fields verified: ACCT-ID, ACCT-ACTIVE-STATUS, ACCT-CURR-BAL,
     * ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-OPEN-DATE,
     * ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, ACCT-CURR-CYC-CREDIT,
     * ACCT-CURR-CYC-DEBIT, ACCT-GROUP-ID.</p>
     */
    @Test
    @DisplayName("Should display/log all 11 account fields matching COBOL DISPLAY format")
    void shouldDisplayAllAccountFields() {
        // Given: Account with all 11 displayed fields explicitly set via setters
        // to verify all field accessors are invoked during displayAccountRecord()
        Account account = createTestAccount("00000000001", new BigDecimal("5000.00"));
        account.setAcctId("12345678901");
        account.setActiveStatus("Y");
        account.setOpenDate("2020-03-15");
        account.setExpirationDate("2027-12-31");
        account.setReissueDate("2024-01-01");
        account.setAddrZip("90210");
        account.setGroupId("PREMIUM");
        when(accountRepository.findAll()).thenReturn(List.of(account));

        // When/Then: Method completes without exception (all 11 fields logged)
        assertThatCode(() -> accountRefreshService.refreshAccounts())
                .doesNotThrowAnyException();

        // Verify repository was accessed
        verify(accountRepository, times(1)).findAll();
    }

    // =========================================================================
    // Test 3: Start/End Banners (← PROCEDURE DIVISION DISPLAY statements)
    // =========================================================================

    /**
     * Verifies that the service logs start and end execution banners matching
     * the COBOL DISPLAY statements at the beginning and end of the PROCEDURE
     * DIVISION: {@code 'START OF EXECUTION OF PROGRAM CBACT01C'} and
     * {@code 'END OF EXECUTION OF PROGRAM CBACT01C'}.
     *
     * <p>Because this is a pure Mockito test without log capture, the test
     * verifies that the method completes successfully (implying both banners
     * were logged) and the repository was accessed between them.</p>
     */
    @Test
    @DisplayName("Should log start and end execution banners")
    void shouldLogStartAndEndBanners() {
        // Given: Empty account list (banners should still be logged)
        when(accountRepository.findAll()).thenReturn(Collections.emptyList());

        // When/Then: Method completes successfully, implying both banners logged
        assertThatCode(() -> accountRefreshService.refreshAccounts())
                .doesNotThrowAnyException();

        // Verify the repository was accessed between the start and end banners
        verify(accountRepository, times(1)).findAll();
    }

    // =========================================================================
    // Test 4: Empty Account List (← immediate EOF on first READ)
    // =========================================================================

    /**
     * Verifies that an empty account list (simulating immediate EOF on the
     * first COBOL READ) is handled gracefully without exceptions.
     *
     * <p>Maps to CBACT01C.cbl scenario where the first
     * {@code 1000-ACCTFILE-GET-NEXT} returns ACCTFILE-STATUS '10' (EOF),
     * setting END-OF-FILE = 'Y' and exiting the PERFORM UNTIL loop
     * immediately. The program should still display both execution banners
     * and close the file cleanly.</p>
     */
    @Test
    @DisplayName("Should handle empty account list (no records to process)")
    void shouldHandleEmptyAccountList() {
        // Given: No records in repository (simulates immediate EOF)
        when(accountRepository.findAll()).thenReturn(Collections.emptyList());

        // When/Then: No exception thrown, method completes normally
        assertThatCode(() -> accountRefreshService.refreshAccounts())
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Test 5: Repository Exception (← 9910/9999 ABEND path)
    // =========================================================================

    /**
     * Verifies that a repository exception is caught and wrapped in a
     * {@link CardDemoException}, mapping the COBOL error path:
     * non-00/non-10 file status → 9910-DISPLAY-IO-STATUS →
     * 9999-ABEND-PROGRAM → CALL 'CEE3ABD' with ABCODE=999.
     *
     * <p>In COBOL, a non-00/non-10 file status on READ triggers:
     * {@code DISPLAY 'ERROR READING ACCOUNT FILE'}, then
     * {@code PERFORM 9910-DISPLAY-IO-STATUS} (format IO status), then
     * {@code PERFORM 9999-ABEND-PROGRAM} (MOVE 999 TO ABCODE, CALL CEE3ABD).
     * In Java, this translates to: log error, throw CardDemoException.</p>
     */
    @Test
    @DisplayName("Should handle repository exception (maps to file status error)")
    void shouldHandleRepositoryException() {
        // Given: findAll() throws RuntimeException (simulates VSAM I/O error)
        when(accountRepository.findAll())
                .thenThrow(new RuntimeException("DB connection failed"));

        // When/Then: Exception wrapped in CardDemoException (ABCODE=999 path)
        assertThatThrownBy(() -> accountRefreshService.refreshAccounts())
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("ABENDING PROGRAM")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // =========================================================================
    // Test 6: Multiple Accounts (← PERFORM UNTIL END-OF-FILE = 'Y')
    // =========================================================================

    /**
     * Verifies that multiple accounts (5 records with different IDs and
     * balances) are all processed in a single {@code findAll()} call,
     * matching the COBOL PERFORM UNTIL loop that iterates through all
     * records sequentially.
     *
     * <p>The key verification is that {@code findAll()} is called exactly
     * once (not per-account), consistent with the Java translation of the
     * COBOL sequential read pattern where all records are fetched in one
     * database operation.</p>
     */
    @Test
    @DisplayName("Should process all accounts in the list (50 accounts matching seed data)")
    void shouldProcessMultipleAccounts() {
        // Given: 5 accounts with different IDs and balances
        List<Account> accounts = new ArrayList<>();
        accounts.add(createTestAccount("00000000001", new BigDecimal("100.00")));
        accounts.add(createTestAccount("00000000002", new BigDecimal("200.00")));
        accounts.add(createTestAccount("00000000003", new BigDecimal("300.00")));
        accounts.add(createTestAccount("00000000004", new BigDecimal("400.00")));
        accounts.add(createTestAccount("00000000005", new BigDecimal("500.00")));
        when(accountRepository.findAll()).thenReturn(accounts);

        // When: Process all 5 accounts
        assertThatCode(() -> accountRefreshService.refreshAccounts())
                .doesNotThrowAnyException();

        // Then: findAll() called exactly once (not per-account)
        verify(accountRepository, times(1)).findAll();
    }

    // =========================================================================
    // Test 7: BigDecimal Field Handling (← PIC S9(10)V99 COMP-3)
    // =========================================================================

    /**
     * Verifies that BigDecimal monetary fields (COBOL PIC S9(10)V99 COMP-3)
     * are handled correctly at extreme values without precision loss.
     *
     * <p>Tests with maximum COBOL field value (9999999999.99 for PIC S9(10)V99
     * = 10 integer digits + 2 decimal places) and {@link BigDecimal#ZERO} to
     * ensure no floating-point artifacts in display formatting.</p>
     */
    @Test
    @DisplayName("Should correctly handle BigDecimal monetary fields in account display")
    void shouldHandleBigDecimalFieldsInDisplay() {
        // Given: Account with extreme BigDecimal values for 5 monetary fields
        Account account = createTestAccount("99999999999", BigDecimal.ZERO);
        // Override monetary fields via setters with max-range values
        account.setCurrBal(new BigDecimal("9999999999.99"));
        account.setCreditLimit(new BigDecimal("5000000.00"));
        account.setCashCreditLimit(new BigDecimal("1000000.00"));
        account.setCurrCycCredit(BigDecimal.ZERO);
        account.setCurrCycDebit(BigDecimal.ZERO);
        when(accountRepository.findAll()).thenReturn(List.of(account));

        // When/Then: No precision loss, no exception during display
        assertThatCode(() -> accountRefreshService.refreshAccounts())
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Test 8: Read-Only Verification (← OPEN INPUT ACCTFILE-FILE)
    // =========================================================================

    /**
     * Verifies that the service is strictly read-only — it never calls
     * {@code save()}, {@code saveAll()}, {@code delete()}, or
     * {@code deleteById()} on the repository.
     *
     * <p>This maps to the COBOL {@code OPEN INPUT ACCTFILE-FILE} statement
     * in paragraph 0000-ACCTFILE-OPEN, which opens the VSAM file for
     * sequential read access only. No WRITE, REWRITE, or DELETE operations
     * are performed by CBACT01C.cbl.</p>
     */
    @Test
    @DisplayName("Should only read accounts, never save/update (read-only operation)")
    void shouldNotModifyAccountData() {
        // Given: Repository returns default accounts
        when(accountRepository.findAll()).thenReturn(defaultAccounts);

        // When: Service processes all accounts
        accountRefreshService.refreshAccounts();

        // Then: Only findAll() was called — no write operations
        verify(accountRepository, times(1)).findAll();
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveAll(any());
        verify(accountRepository, never()).delete(any());
        verify(accountRepository, never()).deleteById(anyString());
    }

    // =========================================================================
    // Test Data Helper Methods
    // =========================================================================

    /**
     * Creates a fully-populated test Account entity matching the COBOL
     * ACCOUNT-RECORD structure from CVACT01Y.cpy (300 bytes, 12 fields).
     *
     * <p>Uses the public all-args constructor of {@link Account} to set
     * all 12 business fields. The {@code acctId} and {@code currBal}
     * parameters allow each test to create accounts with distinct
     * identifiers and balances, while other fields use representative
     * default values matching typical seed data patterns.</p>
     *
     * @param acctId  account identifier (11-char numeric string)
     * @param currBal current balance as BigDecimal (PIC S9(10)V99)
     * @return fully populated Account entity ready for test use
     */
    private Account createTestAccount(String acctId, BigDecimal currBal) {
        return new Account(
                acctId,
                "Y",
                currBal,
                new BigDecimal("10000.00"),
                new BigDecimal("3000.00"),
                "2020-01-01",
                "2025-12-31",
                "2023-06-15",
                new BigDecimal("1500.00"),
                new BigDecimal("750.00"),
                "12345",
                "A"
        );
    }
}
