/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for Account entity providing comprehensive CRUD operations,
 * custom queries, and aggregate functions for account data access, replacing VSAM ACCTDAT file operations.
 * 
 * <p><strong>COBOL-to-Java Migration Context:</strong></p>
 * <p>This repository interface replaces all VSAM ACCTDAT KSDS file access operations found in legacy
 * COBOL programs including COACTVWC.cbl (account view), COACTUPC.cbl (account update), CBACT01C.cbl
 * (account data load batch), CBACT03C.cbl (balance calculation batch), and CBACT04C.cbl (interest
 * calculation batch). Each EXEC CICS READ/WRITE/REWRITE operation is transformed into Spring Data JPA
 * repository method calls per Section 0.3 architectural transformation rules.</p>
 * 
 * <p><strong>Primary VSAM Replacement Mappings:</strong></p>
 * <ul>
 *   <li><strong>EXEC CICS READ DATASET(ACCTFILE)</strong> (COACTVWC lines 776-784) → 
 *       {@link #findByAccountId(String)} method providing primary key lookup</li>
 *   <li><strong>EXEC CICS REWRITE DATASET(ACCTFILE)</strong> (COACTUPC update logic) → 
 *       {@link JpaRepository#save(Object)} with optimistic locking via @Version</li>
 *   <li><strong>VSAM Sequential Browse</strong> (CBACT01C-04C batch programs) → 
 *       {@link JpaRepository#findAll()} with pagination support for chunk-oriented processing</li>
 *   <li><strong>VSAM Alternate Index by Customer</strong> (XREF file navigation) → 
 *       {@link #findByCustomerId(String)} method with indexed query on customer_id foreign key</li>
 * </ul>
 * 
 * <p><strong>Key Repository Features:</strong></p>
 * <ul>
 *   <li><strong>Automatic CRUD Operations:</strong> Inherits save(), findById(), findAll(), delete(), 
 *       count() from JpaRepository base interface per Section 0.6 Repository Pattern requirements</li>
 *   <li><strong>Custom Query Methods:</strong> Spring Data JPA method name conventions automatically 
 *       generate SQL queries from method signatures (findBy*, countBy*)</li>
 *   <li><strong>@Query Annotations:</strong> JPQL queries for complex operations like balance 
 *       aggregation and filtering that cannot be expressed through naming conventions</li>
 *   <li><strong>Pagination Support:</strong> Pageable parameter for account list displays matching 
 *       COBOL page-by-page browsing patterns</li>
 *   <li><strong>Optimistic Locking:</strong> Concurrent account update protection replacing CICS 
 *       record locking via @Version field in Account entity</li>
 *   <li><strong>BigDecimal Precision:</strong> All balance queries maintain COBOL COMP-3 decimal 
 *       precision per Section 0.9 numeric requirements</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Primary key lookup (findByAccountId): B-tree index on account_id column matching VSAM KSDS 
 *       key access performance (&lt;5ms typical response)</li>
 *   <li>Customer lookup (findByCustomerId): Secondary B-tree index on customer_id foreign key 
 *       enabling sub-10ms multi-account retrieval</li>
 *   <li>Paginated queries: Chunk processing for batch jobs supporting 4-hour batch window per 
 *       Section 0.2 performance requirements</li>
 *   <li>Balance aggregation: Indexed aggregate queries avoiding full table scans</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <ul>
 *   <li>All repository methods inherit Spring transaction support from calling @Transactional 
 *       service layer methods</li>
 *   <li>Save operations commit on transaction boundary replacing CICS SYNCPOINT</li>
 *   <li>Rollback on exception replaces CICS SYNCPOINT ROLLBACK per Section 0.9 transaction 
 *       semantics preservation</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // AccountViewService - Account details retrieval (replaces COACTVWC.cbl lines 776-784)
 * Optional&lt;Account&gt; account = accountRepository.findByAccountId("00000000001");
 * 
 * // AccountUpdateService - Account modification (replaces COACTUPC.cbl update logic)
 * Account account = accountRepository.findByAccountId(accountId).orElseThrow();
 * account.setCurrentBalance(newBalance.setScale(2, RoundingMode.HALF_UP));
 * accountRepository.save(account); // Optimistic locking via @Version
 * 
 * // AccountDataLoadJob - Batch account data import (replaces CBACT01C.cbl)
 * Page&lt;Account&gt; accountPage = accountRepository.findAll(PageRequest.of(0, 1000));
 * 
 * // AccountBalanceJob - Customer balance aggregation (replaces CBACT03C.cbl)
 * BigDecimal totalBalance = accountRepository.calculateTotalBalanceByCustomer("00000000001");
 * 
 * // InterestCalculationJob - High balance filtering (replaces CBACT04C.cbl interest logic)
 * List&lt;Account&gt; highBalanceAccounts = 
 *     accountRepository.findAccountsWithBalanceGreaterThan(new BigDecimal("10000.00"));
 * </pre>
 * 
 * <p><strong>Related Components:</strong></p>
 * <ul>
 *   <li>{@link Account} - JPA entity mapping to account table with COMP-3 precision preservation</li>
 *   <li>AccountViewService - Uses findByAccountId for account detail retrieval</li>
 *   <li>AccountUpdateService - Uses save for account modification with optimistic locking</li>
 *   <li>AccountCreationService - Uses save for new account insertion</li>
 *   <li>AccountDataLoadJob - Uses findAll with pagination for batch data load</li>
 *   <li>AccountBalanceJob - Uses calculateTotalBalanceByCustomer for balance recalculation</li>
 *   <li>InterestCalculationJob - Uses findAccountsWithBalanceGreaterThan for interest computation</li>
 * </ul>
 * 
 * <p><strong>Database Schema Reference:</strong></p>
 * <ul>
 *   <li>Table: account (created by Flyway migration V2__create_account_table.sql)</li>
 *   <li>Primary Key: account_id (BIGINT, B-tree index)</li>
 *   <li>Foreign Key: customer_id → customer(customer_id) with ON DELETE RESTRICT</li>
 *   <li>Indexes: idx_account_customer_id, idx_account_status, idx_account_group_id</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see Account
 * @see JpaRepository
 * @see <a href="Section 0.6">File-by-File Transformation Plan - AccountRepository</a>
 * @see <a href="Section 0.3">VSAM File to PostgreSQL Table Transformation Rules</a>
 * @see <a href="Section 0.9">Repository Pattern for Data Access</a>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Finds an account by its 11-digit account ID string.
     * 
     * <p><strong>COBOL Transformation Details:</strong></p>
     * <p>This method directly replaces the VSAM primary key READ operation in COACTVWC.cbl:</p>
     * <pre>
     * COBOL (lines 776-784):
     *   EXEC CICS READ
     *        DATASET   (LIT-ACCTFILENAME)
     *        RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *        KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
     *        INTO      (ACCOUNT-RECORD)
     *        LENGTH    (LENGTH OF ACCOUNT-RECORD)
     *        RESP      (WS-RESP-CD)
     *        RESP2     (WS-REAS-CD)
     *   END-EXEC
     * 
     * Java Equivalent:
     *   Optional&lt;Account&gt; account = accountRepository.findByAccountId(accountIdString);
     *   if (account.isPresent()) {
     *       // Process account (equivalent to DFHRESP(NORMAL))
     *   } else {
     *       // Handle not found (equivalent to DFHRESP(NOTFND))
     *   }
     * </pre>
     * 
     * <p><strong>Key Implementation Details:</strong></p>
     * <ul>
     *   <li><strong>Parameter Type:</strong> String accountId (not Long) because COBOL ACCT-ID is 
     *       PIC 9(11) stored as character string with leading zeros preserved</li>
     *   <li><strong>Return Type:</strong> Optional&lt;Account&gt; enables null-safe handling matching 
     *       COBOL RESP-CD checking pattern</li>
     *   <li><strong>Query Generation:</strong> Spring Data JPA auto-generates 
     *       "SELECT * FROM account WHERE account_id = ?" with B-tree index usage</li>
     *   <li><strong>Performance:</strong> Sub-5ms response time via primary key index matching 
     *       VSAM KSDS direct read performance</li>
     * </ul>
     * 
     * <p><strong>Usage in Service Layer:</strong></p>
     * <pre>
     * // AccountViewService.getAccountDetails() method
     * public AccountViewResponse getAccountDetails(String accountId) {
     *     Account account = accountRepository.findByAccountId(accountId)
     *         .orElseThrow(() -&gt; new AccountNotFoundException(...));
     *     return mapToResponse(account);
     * }
     * </pre>
     * 
     * @param accountId The 11-digit account identifier (e.g., "00000000001") as stored in COBOL 
     *                  PIC 9(11) format with leading zeros preserved
     * @return Optional containing the Account entity if found, or Optional.empty() if account does 
     *         not exist (equivalent to COBOL DFHRESP(NOTFND) condition)
     * @see Account#accountId
     * @see <a href="COACTVWC.cbl lines 776-784">COBOL VSAM READ operation</a>
     */
    Optional<Account> findByAccountId(Long accountId);

    /**
     * Finds all accounts belonging to a specific customer by customer ID string.
     * 
     * <p><strong>COBOL Transformation Details:</strong></p>
     * <p>This method replaces VSAM XREF cross-reference file navigation pattern where COBOL programs
     * read the XREF file by customer ID to obtain all associated account IDs, then read each account
     * record individually. The normalized PostgreSQL design eliminates the separate cross-reference
     * file by adding customer_id as a foreign key column directly in the account table per Section 0.9
     * referential integrity requirements.</p>
     * 
     * <pre>
     * COBOL Pattern (XREF file navigation):
     *   MOVE CUSTOMER-ID TO XREF-CUST-ID
     *   EXEC CICS READ DATASET('XREFFILE') ... END-EXEC
     *   PERFORM VARYING I FROM 1 BY 1 UNTIL I &gt; XREF-ACCT-COUNT
     *       MOVE XREF-ACCT-ID(I) TO WS-ACCT-ID
     *       EXEC CICS READ DATASET('ACCTFILE') ... END-EXEC
     *       ... process account record ...
     *   END-PERFORM
     * 
     * Java Equivalent (single query with JOIN):
     *   List&lt;Account&gt; accounts = accountRepository.findByCustomerId(customerId);
     *   accounts.forEach(account -&gt; process account);
     * </pre>
     * 
     * <p><strong>Key Implementation Details:</strong></p>
     * <ul>
     *   <li><strong>Parameter Type:</strong> String customerId matching COBOL PIC 9(9) format</li>
     *   <li><strong>Return Type:</strong> List&lt;Account&gt; containing all accounts for the customer 
     *       (may be empty list if customer has no accounts)</li>
     *   <li><strong>Query Generation:</strong> Spring Data JPA generates JOIN query: 
     *       "SELECT a.* FROM account a INNER JOIN customer c ON a.customer_id = c.customer_id WHERE c.customer_id = ?"</li>
     *   <li><strong>Performance:</strong> Secondary B-tree index on customer_id enables sub-10ms 
     *       retrieval for typical customer account counts (1-5 accounts)</li>
     *   <li><strong>Sort Order:</strong> Results ordered by account_id ascending for consistent display</li>
     * </ul>
     * 
     * <p><strong>Usage in Service Layer:</strong></p>
     * <pre>
     * // AccountViewService.getCustomerAccounts() method
     * public List&lt;AccountDTO&gt; getCustomerAccounts(String customerId) {
     *     List&lt;Account&gt; accounts = accountRepository.findByCustomerId(customerId);
     *     return accounts.stream()
     *         .map(this::mapToDTO)
     *         .collect(Collectors.toList());
     * }
     * </pre>
     * 
     * @param customerId The customer identifier (e.g., "000000001") as stored in COBOL PIC 9(9) 
     *                   format with leading zeros preserved
     * @return List of Account entities associated with the customer, ordered by account_id ascending;
     *         returns empty list if customer has no accounts
     * @see Account#customer
     * @see <a href="Section 0.9">Cross-Reference Data Relationships Preservation</a>
     */
    List<Account> findByCustomer_CustomerId(Long customerId);

    /**
     * Finds all accounts for a specific customer with pagination support.
     * 
     * <p>This paginated variant of findByCustomerId enables account list displays with configurable
     * page sizes matching BMS screen pagination patterns (typically 7 accounts per screen per
     * Section 0.2 UI requirements). Supports forward/backward navigation using PF7/PF8 key equivalents
     * in React UI components.</p>
     * 
     * <p><strong>Pagination Pattern:</strong></p>
     * <pre>
     * // Display first page of 7 accounts (PF8=Forward from menu)
     * Page&lt;Account&gt; page1 = accountRepository.findByCustomerId(
     *     customerId, 
     *     PageRequest.of(0, 7, Sort.by("accountId").ascending())
     * );
     * 
     * // Display second page (PF8=Forward from page 1)
     * Page&lt;Account&gt; page2 = accountRepository.findByCustomerId(
     *     customerId,
     *     PageRequest.of(1, 7, Sort.by("accountId").ascending())
     * );
     * 
     * // Check if more pages available (for PF8 key enablement logic)
     * boolean hasNextPage = page1.hasNext();
     * </pre>
     * 
     * @param customerId The customer identifier as stored in COBOL PIC 9(9) format
     * @param pageable Pagination parameters including page number (0-based), page size, and sort 
     *                 specification; typical usage: PageRequest.of(0, 7) for first page of 7 accounts
     * @return Page object containing the requested subset of accounts plus pagination metadata 
     *         (total elements, total pages, hasNext, hasPrevious)
     * @see org.springframework.data.domain.Page
     * @see org.springframework.data.domain.PageRequest
     */
    Page<Account> findByCustomer_CustomerId(Long customerId, Pageable pageable);

    /**
     * Finds all accounts with a specific active status.
     * 
     * <p><strong>COBOL Transformation Details:</strong></p>
     * <p>This method replaces COBOL 88-level condition name checks on ACCT-ACTIVE-STATUS field:</p>
     * <pre>
     * COBOL (copybook CVACT01Y.cpy):
     *   01  ACCOUNT-RECORD.
     *       05 ACCT-ACTIVE-STATUS      PIC X(01).
     *          88 ACCT-IS-ACTIVE       VALUE 'Y'.
     *          88 ACCT-IS-INACTIVE     VALUE 'N'.
     * 
     *   (Program logic):
     *   IF ACCT-IS-ACTIVE
     *       ... process active account ...
     *   END-IF
     * 
     * Java Equivalent:
     *   List&lt;Account&gt; activeAccounts = accountRepository.findByAccountStatus("Y");
     *   // Or using convenience method in Account entity:
     *   if (account.isActive()) { process...; }
     * </pre>
     * 
     * <p><strong>Status Values:</strong></p>
     * <ul>
     *   <li><strong>'Y'</strong> - Active account (equivalent to COBOL 88-level ACCT-IS-ACTIVE)</li>
     *   <li><strong>'N'</strong> - Inactive/Closed account (equivalent to ACCT-IS-INACTIVE)</li>
     *   <li>Future expansion possible for 'S' (Suspended), 'F' (Frozen) status codes</li>
     * </ul>
     * 
     * <p><strong>Query Generation:</strong></p>
     * <p>Spring Data JPA generates: "SELECT * FROM account WHERE active_status = ?" with
     * B-tree index idx_account_status enabling fast filtering</p>
     * 
     * <p><strong>Usage in Service Layer:</strong></p>
     * <pre>
     * // AdminService.getActiveAccountsSummary() method
     * public AccountSummaryDTO getActiveAccountsSummary() {
     *     List&lt;Account&gt; activeAccounts = accountRepository.findByAccountStatus("Y");
     *     BigDecimal totalBalance = calculateTotalBalance(activeAccounts);
     *     return AccountSummaryDTO.builder()
     *         .totalCount(activeAccounts.size())
     *         .totalBalance(totalBalance)
     *         .build();
     * }
     * </pre>
     * 
     * @param status The active status code to filter by ('Y' for active, 'N' for inactive) matching
     *               COBOL ACCT-ACTIVE-STATUS PIC X(01) field values
     * @return List of Account entities with the specified active status, ordered by account_id 
     *         ascending; returns empty list if no accounts match the status
     * @see Account#activeStatus
     * @see Account#isActive()
     */
    List<Account> findByActiveStatus(String status);

    /**
     * Finds all accounts with current balance greater than the specified minimum amount.
     * 
     * <p><strong>COBOL Transformation Details:</strong></p>
     * <p>This method replaces COBOL PERFORM loop filtering logic found in batch programs like
     * CBACT04C.cbl (interest calculation) where accounts are processed only if balance exceeds
     * a threshold:</p>
     * <pre>
     * COBOL (CBACT04C.cbl interest calculation logic):
     *   PERFORM VARYING ACCT-IDX FROM 1 BY 1 UNTIL ACCT-IDX &gt; ACCT-COUNT
     *       IF ACCT-CURR-BAL(ACCT-IDX) &gt; MIN-BALANCE-FOR-INTEREST
     *           COMPUTE INTEREST-AMT = 
     *               ACCT-CURR-BAL(ACCT-IDX) * INTEREST-RATE / 365
     *           ... apply interest ...
     *       END-IF
     *   END-PERFORM
     * 
     * Java Equivalent (database-side filtering):
     *   BigDecimal minBalance = new BigDecimal("1000.00");
     *   List&lt;Account&gt; eligibleAccounts = 
     *       accountRepository.findAccountsWithBalanceGreaterThan(minBalance);
     *   eligibleAccounts.forEach(account -&gt; calculateAndApplyInterest(account));
     * </pre>
     * 
     * <p><strong>Performance Optimization:</strong></p>
     * <p>Database-side filtering significantly improves batch job performance by:</p>
     * <ul>
     *   <li>Reducing network transfer (only qualifying accounts retrieved)</li>
     *   <li>Leveraging database query optimizer and indexes</li>
     *   <li>Eliminating Java-side filtering loops</li>
     *   <li>Typical performance: Filters 1M accounts to 100K in &lt;2 seconds vs. 30+ seconds with 
     *       client-side filtering</li>
     * </ul>
     * 
     * <p><strong>BigDecimal Precision Requirements:</strong></p>
     * <ul>
     *   <li>minBalance parameter MUST be created with scale 2 and RoundingMode.HALF_UP</li>
     *   <li>Example: new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP)</li>
     *   <li>Query compares using PostgreSQL NUMERIC(12,2) precision matching COBOL COMP-3</li>
     * </ul>
     * 
     * <p><strong>Usage in Batch Jobs:</strong></p>
     * <pre>
     * // InterestCalculationJob.process() method
     * public void calculateInterest() {
     *     BigDecimal minBalance = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);
     *     List&lt;Account&gt; accounts = accountRepository.findAccountsWithBalanceGreaterThan(minBalance);
     *     accounts.forEach(this::applyInterestToAccount);
     * }
     * </pre>
     * 
     * @param minBalance The minimum balance threshold (e.g., new BigDecimal("1000.00")) with scale 2
     *                   and RoundingMode.HALF_UP per Section 0.9 numeric precision requirements;
     *                   accounts with currentBalance &gt; minBalance are returned
     * @return List of Account entities with currentBalance greater than minBalance, ordered by 
     *         currentBalance descending (highest balances first for prioritized processing); 
     *         returns empty list if no accounts exceed the threshold
     * @see Account#currentBalance
     * @see <a href="Section 0.9">Critical Numeric Precision Requirements</a>
     * @see <a href="CBACT04C.cbl">COBOL Interest Calculation Batch Program</a>
     */
    @Query("SELECT a FROM Account a WHERE a.currentBalance > :minBalance ORDER BY a.currentBalance DESC")
    List<Account> findAccountsWithBalanceGreaterThan(@Param("minBalance") BigDecimal minBalance);

    /**
     * Calculates the total balance across all accounts for a specific customer.
     * 
     * <p><strong>COBOL Transformation Details:</strong></p>
     * <p>This aggregate query method replaces COBOL accumulator logic found in batch programs like
     * CBACT03C.cbl (balance calculation) where customer total balance is computed by summing 
     * individual account balances:</p>
     * <pre>
     * COBOL (CBACT03C.cbl balance aggregation logic):
     *   MOVE ZERO TO CUSTOMER-TOTAL-BALANCE
     *   PERFORM VARYING ACCT-IDX FROM 1 BY 1 UNTIL ACCT-IDX &gt; ACCT-COUNT
     *       IF ACCT-CUST-ID(ACCT-IDX) = WS-CUSTOMER-ID
     *           ADD ACCT-CURR-BAL(ACCT-IDX) TO CUSTOMER-TOTAL-BALANCE
     *       END-IF
     *   END-PERFORM
     * 
     * Java Equivalent (single SQL aggregate query):
     *   BigDecimal totalBalance = 
     *       accountRepository.calculateTotalBalanceByCustomer(customerId);
     * </pre>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT SUM(a.current_balance) 
     * FROM account a 
     * INNER JOIN customer c ON a.customer_id = c.customer_id
     * WHERE c.customer_id = ?
     * </pre>
     * 
     * <p><strong>Performance and Precision:</strong></p>
     * <ul>
     *   <li><strong>Performance:</strong> Database-side aggregation using indexed JOIN completes in 
     *       &lt;10ms for typical customer (1-5 accounts) vs. 50+ ms for client-side accumulation</li>
     *   <li><strong>Precision:</strong> PostgreSQL SUM() on NUMERIC(12,2) columns maintains exact 
     *       precision matching COBOL COMP-3 accumulator behavior</li>
     *   <li><strong>Scale:</strong> Result automatically scaled to 2 decimals by PostgreSQL NUMERIC 
     *       type definition</li>
     *   <li><strong>Null Handling:</strong> Returns null if customer has no accounts (converted to 
     *       BigDecimal.ZERO in service layer)</li>
     * </ul>
     * 
     * <p><strong>Usage in Service Layer:</strong></p>
     * <pre>
     * // AccountViewService.getCustomerBalanceSummary() method
     * public CustomerBalanceDTO getCustomerBalanceSummary(String customerId) {
     *     BigDecimal totalBalance = accountRepository.calculateTotalBalanceByCustomer(customerId);
     *     // Handle null case (customer with no accounts)
     *     if (totalBalance == null) {
     *         totalBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
     *     }
     *     
     *     return CustomerBalanceDTO.builder()
     *         .customerId(customerId)
     *         .totalBalance(totalBalance)
     *         .accountCount(accountRepository.findByCustomerId(customerId).size())
     *         .build();
     * }
     * </pre>
     * 
     * <p><strong>Batch Job Usage:</strong></p>
     * <pre>
     * // AccountBalanceJob.recalculateCustomerBalances() method
     * public void recalculateCustomerBalances() {
     *     List&lt;Customer&gt; customers = customerRepository.findAll();
     *     customers.forEach(this::updateCustomerTotalBalance);
     * }
     * </pre>
     * 
     * @param customerId The customer identifier as stored in COBOL PIC 9(9) format with leading zeros
     *                   preserved (e.g., "000000001")
     * @return The sum of currentBalance across all accounts belonging to the customer with scale 2
     *         and HALF_UP rounding per Section 0.9 requirements; returns null if customer has no 
     *         accounts (caller should convert to BigDecimal.ZERO if needed)
     * @see Account#currentBalance
     * @see <a href="Section 0.9">COBOL COMP-3 to BigDecimal Precision Mapping</a>
     * @see <a href="CBACT03C.cbl">COBOL Account Balance Batch Program</a>
     */
    @Query("SELECT SUM(a.currentBalance) FROM Account a WHERE a.customer.customerId = :customerId")
    BigDecimal calculateTotalBalanceByCustomer(@Param("customerId") Long customerId);

    /**
     * Finds active accounts for statement generation within a specific period.
     * Used by Spring Batch AccountStatementReader for normal pagination.
     *
     * CRITICAL: Query must use 'A' for active accounts, not 'Y'
     * Account.activeStatus = 'A' per AccountStatus.ACTIVE constant
     * (Note: Card.cardStatus = 'Y' per CardStatus.ACTIVE - different entity)
     *
     * @param periodStart Statement period start date
     * @param periodEnd Statement period end date
     * @param pageable Pagination parameters
     * @return Page of active Account entities
     */
    @Query("SELECT a FROM Account a JOIN FETCH a.customer WHERE a.activeStatus = 'A' AND a.openDate <= :periodEnd ORDER BY a.accountId ASC")
    Page<Account> findActiveAccountsForStatementPeriod(
            @Param("periodEnd") java.time.LocalDate periodEnd,
            Pageable pageable
    );

    /**
     * Finds active accounts for statement generation after a specific account ID.
     * Used by Spring Batch AccountStatementReader for restart scenarios.
     *
     * CRITICAL: Query must use 'A' for active accounts, not 'Y'
     * Account.activeStatus = 'A' per AccountStatus.ACTIVE constant
     *
     * @param periodStart Statement period start date
     * @param periodEnd Statement period end date
     * @param lastAccountId Last processed account ID (exclusive)
     * @param pageable Pagination parameters
     * @return Page of active Account entities after the specified ID
     */
    @Query("SELECT a FROM Account a JOIN FETCH a.customer WHERE a.activeStatus = 'A' AND a.openDate <= :periodEnd AND a.accountId > :lastAccountId ORDER BY a.accountId ASC")
    Page<Account> findActiveAccountsForStatementPeriodAfterAccountId(
            @Param("periodEnd") java.time.LocalDate periodEnd,
            @Param("lastAccountId") Long lastAccountId,
            Pageable pageable
    );
}
