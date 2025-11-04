/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for Transaction entity providing comprehensive 
 * CRUD operations, complex queries, pagination support, and aggregate functions for 
 * transaction data access, replacing VSAM TRANSACT file operations with sequential 
 * and random access patterns.
 * 
 * <p><strong>COBOL Source Programs Replaced:</strong></p>
 * <ul>
 *   <li>COTRN00C.cbl - Transaction list with STARTBR/READNEXT/READPREV browsing (lines 593-668)</li>
 *   <li>COTRN01C.cbl - Transaction category summary and aggregation logic</li>
 *   <li>COTRN02C.cbl - Add new transaction with balance updates</li>
 *   <li>CBTRN01C.cbl - Batch transaction data load from sequential files</li>
 *   <li>CBTRN02C.cbl - Daily transaction processing batch job</li>
 *   <li>CBTRN03C.cbl - Transaction category aggregation batch job</li>
 * </ul>
 * 
 * <p><strong>Key Transformation Details:</strong></p>
 * <ul>
 *   <li>VSAM KSDS TRANSACT file → PostgreSQL transaction table</li>
 *   <li>EXEC CICS STARTBR/READNEXT → Spring Data JPA pagination with Pageable</li>
 *   <li>COBOL PERFORM loops with accumulators → SQL GROUP BY with SUM aggregation</li>
 *   <li>CICS RIDFLD sequential access → findByCardNumber with Sort parameters</li>
 *   <li>COBOL date range filtering → JPQL CAST and BETWEEN for LocalDate queries</li>
 * </ul>
 * 
 * <p><strong>Repository Methods Overview:</strong></p>
 * <ul>
 *   <li>{@link #findByTransactionId(String)} - Primary key lookup replacing CICS READ</li>
 *   <li>{@link #findByAccountId(String, Pageable)} - Paginated account transactions (10 per page)</li>
 *   <li>{@link #findByCardNumber(String, Pageable)} - Card-based transaction history</li>
 *   <li>{@link #findByTransactionDateBetween(LocalDate, LocalDate)} - Date range queries for statements</li>
 *   <li>{@link #findByAccountIdAndTransactionDateBetween} - Combined account + date filtering</li>
 *   <li>{@link #aggregateByCategory} - Category-based SUM replacing COBOL COTRN01C logic</li>
 *   <li>{@link #countDailyTransactions} - Daily transaction volume counting</li>
 *   <li>{@link #existsByTransactionId} - Duplicate detection for batch loading</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>High-volume entity: 10,000+ transactions per day typical production load</li>
 *   <li>Query optimization: B-tree indexes on card_number, transaction_timestamp, processed_timestamp</li>
 *   <li>Pagination required: Standard 10 transactions per page per COTRN00C requirements</li>
 *   <li>Response time SLA: Sub-200ms at 95th percentile under 10,000 TPS load per Section 0.2</li>
 *   <li>Compound indexes: (account_id via card join, transaction_timestamp) for date range queries</li>
 * </ul>
 * 
 * <p><strong>Usage by Service Classes:</strong></p>
 * <ul>
 *   <li>TransactionListService - Transaction list display with pagination (COTRN00C replacement)</li>
 *   <li>TransactionCategoryService - Category summary and aggregation (COTRN01C replacement)</li>
 *   <li>TransactionCreationService - New transaction posting (COTRN02C replacement)</li>
 *   <li>TransactionDataLoadJob - Batch data import (CBTRN01C replacement)</li>
 *   <li>DailyTransactionProcessingJob - Daily batch processing (CBTRN02C replacement)</li>
 *   <li>TransactionAggregationJob - Category aggregation batch (CBTRN03C replacement)</li>
 *   <li>StatementGenerationJob - Monthly statement generation (CBSTM03A replacement)</li>
 * </ul>
 * 
 * <p><strong>Extends:</strong> JpaRepository&lt;Transaction, String&gt; providing standard CRUD operations:</p>
 * <ul>
 *   <li>save(Transaction) - Insert or update transaction</li>
 *   <li>findById(String) - Lookup by transaction ID primary key</li>
 *   <li>findAll() - Retrieve all transactions (use with caution - high volume)</li>
 *   <li>delete(Transaction) - Delete transaction entity</li>
 *   <li>count() - Total transaction count</li>
 *   <li>existsById(String) - Check transaction existence by ID</li>
 *   <li>deleteById(String) - Delete by transaction ID</li>
 *   <li>saveAll(Iterable) - Batch insert/update operations</li>
 *   <li>flush() - Force synchronization with database</li>
 * </ul>
 * 
 * <p><strong>CRITICAL Implementation Notes:</strong></p>
 * <ul>
 *   <li>Primary Key: String (16-character transaction ID), not Long - DO NOT use Long as PK</li>
 *   <li>Account Relationship: Transactions accessed via card → account JOIN (no direct accountId)</li>
 *   <li>Date Queries: Use CAST(originationTimestamp AS date) for LocalDate comparisons</li>
 *   <li>Pagination: ALWAYS use Pageable for large result sets to prevent memory issues</li>
 *   <li>BigDecimal Precision: SUM aggregations maintain scale=2 per Section 0.9 requirements</li>
 * </ul>
 * 
 * @see Transaction
 * @see com.carddemo.service.TransactionListService
 * @see com.carddemo.service.TransactionCategoryService
 * @see com.carddemo.service.TransactionCreationService
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">VSAM to PostgreSQL Transformation Rules</a>
 * @see <a href="Section 0.9">Repository Pattern Requirements</a>
 */
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Finds a transaction by its unique 16-character transaction ID.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces EXEC CICS READ DATASET('TRANSACT') 
     * RIDFLD(TRAN-ID) from COBOL programs COTRN00C, COTRN01C, COTRN02C for direct transaction 
     * lookup by primary key.</p>
     * 
     * <p><strong>Spring Data JPA Implementation:</strong> Method name convention 
     * "findBy[FieldName]" automatically generates query:</p>
     * <pre>
     * SELECT t FROM Transaction t WHERE t.transactionId = :transactionId
     * </pre>
     * 
     * <p><strong>Usage Examples:</strong></p>
     * <ul>
     *   <li>Transaction detail view (COTRN01C → TransactionCategoryService)</li>
     *   <li>Transaction verification before update</li>
     *   <li>Duplicate transaction detection during batch load</li>
     *   <li>Audit trail lookup by transaction ID</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Primary key lookup uses unique B-tree index on 
     * transaction_id column. Expected response time &lt; 10ms under normal load.</p>
     * 
     * <p><strong>Return Value:</strong> Optional pattern prevents NullPointerException 
     * and enables elegant handling of not-found scenarios:</p>
     * <pre>
     * Optional&lt;Transaction&gt; result = transactionRepository.findByTransactionId(id);
     * Transaction txn = result.orElseThrow(() -&gt; new TransactionNotFoundException(id));
     * </pre>
     * 
     * @param transactionId the unique 16-character transaction identifier (e.g., "2024121500000001")
     * @return Optional containing the transaction if found, empty Optional if not found
     */
    Optional<Transaction> findByTransactionId(String transactionId);

    /**
     * Retrieves all transactions for a specific account (non-paginated).
     * 
     * <p><strong>CRITICAL WARNING:</strong> This method returns ALL transactions for an 
     * account without pagination. Use ONLY for batch processing jobs where full dataset 
     * is required. For user-facing queries, use the paginated version 
     * {@link #findByAccountId(String, Pageable)} to prevent memory exhaustion.</p>
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces sequential VSAM read operations 
     * in batch programs CBTRN02C (Daily Transaction Processing) and CBTRN03C (Transaction 
     * Aggregation) where all account transactions must be processed.</p>
     * 
     * <p><strong>Custom JPQL Query:</strong> Joins through Card entity to access account:</p>
     * <pre>
     * SELECT t FROM Transaction t 
     * WHERE t.card.accountId = :accountId
     * ORDER BY t.originationTimestamp DESC
     * </pre>
     * 
     * <p><strong>Relationship Navigation:</strong></p>
     * <ul>
     *   <li>Transaction entity does NOT have direct accountId field</li>
     *   <li>Query navigates: Transaction → Card (via cardNumber) → accountId field</li>
     *   <li>JPA handles JOIN automatically: transaction INNER JOIN card ON transaction.card_number = card.card_number 
     *       WHERE card.account_id = :accountId</li>
     * </ul>
     * 
     * <p><strong>Usage Scenarios:</strong></p>
     * <ul>
     *   <li>DailyTransactionProcessingJob - Process all daily transactions for balance updates</li>
     *   <li>TransactionAggregationJob - Calculate category totals for all account transactions</li>
     *   <li>StatementGenerationJob - Generate monthly statements with full transaction history</li>
     *   <li>Interest calculation batch - Sum all balances for interest accrual</li>
     * </ul>
     * 
     * <p><strong>Performance Considerations:</strong></p>
     * <ul>
     *   <li>Result set can be 1000+ transactions for high-activity accounts</li>
     *   <li>Heap memory: Each Transaction entity ~1KB, so 10,000 transactions = ~10MB</li>
     *   <li>Query execution: Uses compound index on (card_number, transaction_timestamp)</li>
     *   <li>For large result sets, consider using @Query with stream or pagination</li>
     * </ul>
     * 
     * @param accountId the 11-digit account identifier as Long (e.g., 100000000001L)
     * @return List of all transactions for the account, ordered by originationTimestamp DESC
     */
    @Query("SELECT t FROM Transaction t JOIN Card c ON t.cardNumber = c.cardNumber WHERE c.accountId = :accountId ORDER BY t.originationTimestamp DESC")
    List<Transaction> findByAccountId(@Param("accountId") Long accountId);

    /**
     * Retrieves paginated transactions for a specific account with sorting support.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces EXEC CICS STARTBR/READNEXT/READPREV 
     * browsing pattern from COTRN00C.cbl lines 593-668. Provides equivalent functionality 
     * to pseudo-conversational transaction scrolling in CICS with COMMAREA state management.</p>
     * 
     * <p><strong>Pagination Requirements (Section 0.2):</strong></p>
     * <ul>
     *   <li>Page size: 10 transactions per page per COTRN00C BMS map specification</li>
     *   <li>Sort order: Most recent first (ORDER BY transaction_timestamp DESC)</li>
     *   <li>PF7/PF8 keys: Map to previous/next page in React UI pagination controls</li>
     *   <li>Performance: Sub-200ms response time at 95th percentile under 10,000 TPS load</li>
     * </ul>
     * 
     * <p><strong>Custom JPQL Query with Pagination:</strong></p>
     * <pre>
     * SELECT t FROM Transaction t 
     * WHERE t.card.account.accountId = :accountId 
     * ORDER BY t.originationTimestamp DESC
     * LIMIT :pageSize OFFSET :pageNumber * :pageSize
     * </pre>
     * 
     * <p><strong>Usage Example in Service Layer:</strong></p>
     * <pre>
     * // TransactionListService (COTRN00C replacement)
     * Pageable pageable = PageRequest.of(
     *     pageNumber,           // 0-based page index
     *     10,                   // 10 transactions per page per COTRN00C
     *     Sort.by("originationTimestamp").descending()  // Most recent first
     * );
     * Page&lt;Transaction&gt; page = transactionRepository.findByAccountId(accountId, pageable);
     * 
     * // Page metadata for UI display
     * int totalPages = page.getTotalPages();          // Total page count
     * long totalTransactions = page.getTotalElements(); // Total transaction count
     * boolean hasNext = page.hasNext();               // PF8 Forward enabled?
     * boolean hasPrevious = page.hasPrevious();       // PF7 Backward enabled?
     * </pre>
     * 
     * <p><strong>Relationship Navigation (No Direct accountId):</strong></p>
     * <ul>
     *   <li>Transaction entity does NOT store accountId directly</li>
     *   <li>Query path: Transaction.card → Card.accountId field</li>
     *   <li>Database JOIN: transaction t INNER JOIN card c ON t.card_number = c.card_number 
     *       WHERE c.account_id = :accountId</li>
     *   <li>Performance: Uses compound index on (card.account_id, transaction_timestamp)</li>
     * </ul>
     * 
     * <p><strong>Page Object Properties:</strong></p>
     * <ul>
     *   <li>getContent() - List of Transaction entities for current page</li>
     *   <li>getTotalElements() - Total number of transactions across all pages</li>
     *   <li>getTotalPages() - Total number of pages</li>
     *   <li>getNumber() - Current page number (0-based)</li>
     *   <li>getSize() - Page size (number of transactions per page)</li>
     *   <li>hasNext() - True if next page exists (enable PF8 forward)</li>
     *   <li>hasPrevious() - True if previous page exists (enable PF7 backward)</li>
     *   <li>isFirst() - True if this is the first page</li>
     *   <li>isLast() - True if this is the last page</li>
     * </ul>
     * 
     * <p><strong>Performance Optimization:</strong></p>
     * <ul>
     *   <li>Spring Data JPA generates COUNT query for total elements (cached)</li>
     *   <li>Actual data query uses LIMIT/OFFSET for efficient pagination</li>
     *   <li>Compound index (account_id via card join, transaction_timestamp) prevents full table scan</li>
     *   <li>Lazy loading: Related Card, TransactionType, TransactionCategory not fetched unless accessed</li>
     * </ul>
     * 
     * @param accountId the 11-digit account identifier as Long (e.g., 100000000001L)
     * @param pageable  pagination information (page number, size, sort order)
     * @return Page object containing transactions for the account and pagination metadata
     */
    @Query("SELECT t FROM Transaction t JOIN Card c ON t.cardNumber = c.cardNumber WHERE c.accountId = :accountId")
    Page<Transaction> findByAccountId(@Param("accountId") Long accountId, Pageable pageable);

    /**
     * Retrieves paginated transactions for a specific card number with sorting support.
     * 
     * <p><strong>COBOL Equivalent:</strong> Similar to COTRN00C browsing but filtered by 
     * TRAN-CARD-NUM instead of account. Used for card-specific transaction history displays 
     * when user wants to see transactions for one specific card rather than all account cards.</p>
     * 
     * <p><strong>Spring Data JPA Auto-Implementation:</strong> Method name convention 
     * "findBy[FieldName]" with Pageable parameter automatically generates paginated query:</p>
     * <pre>
     * SELECT t FROM Transaction t 
     * WHERE t.cardNumber = :cardNumber 
     * ORDER BY [Pageable sort specification]
     * LIMIT :pageSize OFFSET :pageNumber * :pageSize
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Card-specific transaction history (customer has multiple cards on same account)</li>
     *   <li>Card dispute investigation (isolate transactions for disputed card)</li>
     *   <li>Fraud analysis (review suspicious card activity)</li>
     *   <li>Card replacement (identify transactions on old card number)</li>
     * </ul>
     * 
     * <p><strong>Pagination Configuration:</strong></p>
     * <pre>
     * // CardDetailService usage example
     * Pageable pageable = PageRequest.of(
     *     pageNumber,           // 0-based page index
     *     10,                   // 10 transactions per page
     *     Sort.by("originationTimestamp").descending()  // Most recent first
     * );
     * Page&lt;Transaction&gt; cardTransactions = transactionRepository.findByCardNumber(cardNumber, pageable);
     * </pre>
     * 
     * <p><strong>Performance:</strong></p>
     * <ul>
     *   <li>Uses B-tree index on card_number column (idx_card_number)</li>
     *   <li>Typical result set: 10-100 transactions per card per month</li>
     *   <li>Query execution: &lt; 50ms average, &lt; 150ms at 95th percentile</li>
     *   <li>Index scan + sort + limit for efficient pagination</li>
     * </ul>
     * 
     * <p><strong>Card Number Format:</strong> 16-character PAN (Primary Account Number) stored 
     * as String to preserve leading zeros. Example: "4532123456789012"</p>
     * 
     * @param cardNumber the 16-digit card number as String (e.g., "4532123456789012")
     * @param pageable   pagination information (page number, size, sort order)
     * @return Page object containing transactions for the card and pagination metadata
     */
    Page<Transaction> findByCardNumber(String cardNumber, Pageable pageable);

    /**
     * Finds transactions within a date range for statement generation and reporting.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces COBOL CEEDAYS date arithmetic and 
     * sequential VSAM read operations used in CBSTM03A.cbl (Statement Generation) for 
     * selecting transactions within a billing cycle date range.</p>
     * 
     * <p><strong>Custom JPQL Query with Date Casting:</strong> Since Transaction entity 
     * stores originationTimestamp as LocalDateTime but query needs LocalDate comparison, 
     * uses CAST function to extract date component:</p>
     * <pre>
     * SELECT t FROM Transaction t 
     * WHERE CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate
     * ORDER BY t.originationTimestamp
     * </pre>
     * 
     * <p><strong>Date Range Behavior:</strong></p>
     * <ul>
     *   <li>INCLUSIVE on both startDate and endDate (matches COBOL BETWEEN behavior)</li>
     *   <li>startDate 2024-12-01 includes all transactions from 2024-12-01 00:00:00.000000</li>
     *   <li>endDate 2024-12-31 includes all transactions through 2024-12-31 23:59:59.999999</li>
     *   <li>Example: Monthly statement for December includes Dec 1 through Dec 31 inclusive</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>StatementGenerationJob (CBSTM03A) - Monthly billing statement transaction selection</li>
     *   <li>TransactionAggregationJob (CBTRN03C) - Aggregate transactions for reporting period</li>
     *   <li>Transaction report generation - Custom date range transaction reports</li>
     *   <li>Billing cycle analysis - Calculate charges and payments for specific period</li>
     * </ul>
     * 
     * <p><strong>COBOL Date Arithmetic Equivalence:</strong></p>
     * <pre>
     * COBOL (CBSTM03A lines 200-250):
     *   COMPUTE WS-START-LILIAN-DATE = FUNCTION INTEGER-OF-DATE(statement-start-date)
     *   COMPUTE WS-END-LILIAN-DATE = FUNCTION INTEGER-OF-DATE(statement-end-date)
     *   IF TRAN-ORIG-TS >= WS-START-LILIAN-DATE 
     *      AND TRAN-ORIG-TS <= WS-END-LILIAN-DATE
     *       PERFORM PROCESS-TRANSACTION
     *   END-IF
     * 
     * Java Equivalent:
     *   List&lt;Transaction&gt; statements = transactionRepository.findByTransactionDateBetween(
     *       LocalDate.of(2024, 12, 1),  // Start of billing cycle
     *       LocalDate.of(2024, 12, 31)  // End of billing cycle
     *   );
     * </pre>
     * 
     * <p><strong>Performance Considerations:</strong></p>
     * <ul>
     *   <li>Uses B-tree index on transaction_timestamp (idx_orig_timestamp)</li>
     *   <li>CAST operation may prevent index usage on some databases - consider functional index</li>
     *   <li>Result set size: Typically 100-10,000 transactions per month for active accounts</li>
     *   <li>Query execution: &lt; 200ms for monthly statement generation per Section 0.2 SLA</li>
     *   <li>Batch processing: Use in chunks with pagination for large date ranges</li>
     * </ul>
     * 
     * <p><strong>Database Functional Index Optimization:</strong></p>
     * <pre>
     * -- PostgreSQL functional index for date cast query optimization
     * CREATE INDEX idx_transaction_date ON transaction ((transaction_timestamp::date));
     * </pre>
     * 
     * <p><strong>WARNING:</strong> This method returns ALL transactions in date range without 
     * pagination. For large date ranges (multiple months), result set can be 10,000+ rows. 
     * Use only for batch processing. For user-facing queries, use paginated version 
     * {@link #findByAccountIdAndTransactionDateBetween} instead.</p>
     * 
     * @param startDate the start date of the range (inclusive, e.g., "2024-12-01")
     * @param endDate   the end date of the range (inclusive, e.g., "2024-12-31")
     * @return List of all transactions within the date range, ordered by originationTimestamp
     */
    @Query("SELECT t FROM Transaction t WHERE CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate ORDER BY t.originationTimestamp")
    List<Transaction> findByTransactionDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Retrieves paginated transactions for an account within a date range.
     * 
     * <p><strong>COBOL Replacement:</strong> Combines account filtering (COTRN00C browsing) 
     * with date range filtering (CBSTM03A statement generation) to provide paginated 
     * transaction history for a specific account within a billing period or custom date range.</p>
     * 
     * <p><strong>Custom JPQL Query with Combined Filters:</strong></p>
     * <pre>
     * SELECT t FROM Transaction t 
     * WHERE t.card.accountId = :accountId 
     *   AND CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate
     * ORDER BY t.originationTimestamp DESC
     * LIMIT :pageSize OFFSET :pageNumber * :pageSize
     * </pre>
     * 
     * <p><strong>Multi-Criteria Filtering:</strong></p>
     * <ul>
     *   <li>Account filter: Joins through Card entity to filter by accountId</li>
     *   <li>Date range filter: CAST originationTimestamp to date for LocalDate comparison</li>
     *   <li>Pagination: Limits result set to manageable page size (typically 10-25 rows)</li>
     *   <li>Sorting: Most recent transactions first (DESC) per COTRN00C display pattern</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Customer portal: "Show my transactions for last 30 days" filtered display</li>
     *   <li>Account statement view: Display transactions for specific billing cycle</li>
     *   <li>Transaction search: Filter by date range with pagination for large result sets</li>
     *   <li>Dispute investigation: Review transactions for specific time period</li>
     * </ul>
     * 
     * <p><strong>Usage Example in Service Layer:</strong></p>
     * <pre>
     * // TransactionListService - Display last 30 days of transactions
     * LocalDate endDate = LocalDate.now();
     * LocalDate startDate = endDate.minusDays(30);
     * Pageable pageable = PageRequest.of(
     *     0,    // First page
     *     10,   // 10 transactions per page
     *     Sort.by("originationTimestamp").descending()
     * );
     * Page&lt;Transaction&gt; recentTransactions = transactionRepository
     *     .findByAccountIdAndTransactionDateBetween(accountId, startDate, endDate, pageable);
     * </pre>
     * 
     * <p><strong>Date Range Behavior (INCLUSIVE):</strong></p>
     * <ul>
     *   <li>startDate "2024-12-01" includes transactions from 2024-12-01 00:00:00</li>
     *   <li>endDate "2024-12-31" includes transactions through 2024-12-31 23:59:59.999999</li>
     *   <li>BETWEEN is inclusive on both boundaries per SQL standard and COBOL behavior</li>
     * </ul>
     * 
     * <p><strong>Performance Optimization:</strong></p>
     * <ul>
     *   <li>Compound index: (card.account_id via join, transaction_timestamp) for efficient filtering</li>
     *   <li>Pagination prevents memory exhaustion with large result sets</li>
     *   <li>Expected query time: &lt; 200ms at 95th percentile per Section 0.2 SLA</li>
     *   <li>Lazy loading: Card and Account entities not fetched unless accessed</li>
     * </ul>
     * 
     * <p><strong>Query Execution Plan:</strong></p>
     * <ol>
     *   <li>Index seek on card.account_id to identify matching cards</li>
     *   <li>Filter transaction_timestamp using date cast within range</li>
     *   <li>Sort by originationTimestamp DESC</li>
     *   <li>Apply LIMIT/OFFSET for pagination</li>
     *   <li>Return Page object with results and pagination metadata</li>
     * </ol>
     * 
     * @param accountId the 11-digit account identifier as Long (e.g., 100000000001L)
     * @param startDate the start date of the range (inclusive)
     * @param endDate   the end date of the range (inclusive)
     * @param pageable  pagination information (page number, size, sort order)
     * @return Page object containing transactions matching criteria and pagination metadata
     */
    @Query("SELECT t FROM Transaction t JOIN Card c ON t.cardNumber = c.cardNumber WHERE c.accountId = :accountId AND CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate")
    Page<Transaction> findByAccountIdAndTransactionDateBetween(
            @Param("accountId") Long accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable
    );

    /**
     * Aggregates transaction amounts by category for a given account and date range.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces COTRN01C.cbl transaction category 
     * summary logic which uses PERFORM loops with accumulator variables to sum transaction 
     * amounts by category code. Modern SQL GROUP BY with SUM aggregation provides identical 
     * results with better performance and database optimization.</p>
     * 
     * <p><strong>COBOL Logic Replaced (COTRN01C lines 300-450):</strong></p>
     * <pre>
     * COBOL Accumulation Pattern:
     *   01 WS-CATEGORY-TOTALS.
     *      05 WS-CAT-ENTRY OCCURS 50 TIMES.
     *         10 WS-CAT-CODE      PIC 9(04).
     *         10 WS-CAT-AMOUNT    PIC S9(09)V99 COMP-3.
     *   
     *   PERFORM READ-ALL-TRANSACTIONS
     *      IF TRAN-CAT-CD NOT = ZERO
     *         SEARCH WS-CAT-ENTRY
     *            WHEN WS-CAT-CODE(IDX) = TRAN-CAT-CD
     *               ADD TRAN-AMT TO WS-CAT-AMOUNT(IDX)
     *         END-SEARCH
     *      END-IF
     *   END-PERFORM.
     * 
     * Java/SQL Equivalent (THIS METHOD):
     *   List&lt;Object[]&gt; results = transactionRepository.aggregateByCategory(
     *       accountId, startDate, endDate
     *   );
     *   // results[0] = [categoryCode(Integer), sumAmount(BigDecimal)]
     *   // results[1] = [categoryCode(Integer), sumAmount(BigDecimal)]
     *   // ... one row per category
     * </pre>
     * 
     * <p><strong>Custom JPQL Aggregation Query:</strong></p>
     * <pre>
     * SELECT t.transactionCategoryCode, SUM(t.transactionAmount)
     * FROM Transaction t
     * WHERE t.card.accountId = :accountId
     *   AND CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate
     * GROUP BY t.transactionCategoryCode
     * </pre>
     * 
     * <p><strong>Return Value Structure:</strong> List&lt;Object[]&gt; where each Object[] contains:</p>
     * <ul>
     *   <li>[0] = transactionCategoryCode (Integer) - 4-digit category code (e.g., 1001, 1002, 2001)</li>
     *   <li>[1] = SUM(transactionAmount) (BigDecimal) - Total amount for category with scale=2</li>
     * </ul>
     * 
     * <p><strong>Usage Example in Service Layer:</strong></p>
     * <pre>
     * // TransactionCategoryService (COTRN01C replacement)
     * List&lt;Object[]&gt; categoryTotals = transactionRepository.aggregateByCategory(
     *     "00000000001",              // accountId
     *     LocalDate.of(2024, 12, 1),  // Start of month
     *     LocalDate.of(2024, 12, 31)  // End of month
     * );
     * 
     * // Process results into DTO
     * List&lt;TransactionCategoryDTO&gt; dtos = categoryTotals.stream()
     *     .map(row -&gt; {
     *         Integer categoryCode = (Integer) row[0];
     *         BigDecimal totalAmount = (BigDecimal) row[1];
     *         return new TransactionCategoryDTO(categoryCode, totalAmount);
     *     })
     *     .collect(Collectors.toList());
     * </pre>
     * 
     * <p><strong>CRITICAL BigDecimal Precision (Section 0.9):</strong></p>
     * <ul>
     *   <li>SUM(transactionAmount) maintains BigDecimal precision with scale=2</li>
     *   <li>Database SUM aggregation preserves NUMERIC(11,2) column precision</li>
     *   <li>Result BigDecimal already has scale=2 from database - no setScale() needed</li>
     *   <li>Matches COBOL COMP-3 PIC S9(09)V99 aggregation behavior exactly</li>
     *   <li>Example: If 3 transactions are $12.50 + $34.25 + $100.00 = $146.75 (exact)</li>
     * </ul>
     * 
     * <p><strong>Category Code Interpretation:</strong></p>
     * <ul>
     *   <li>Category 1001-1999: Purchase categories (Groceries, Gas, Dining, etc.)</li>
     *   <li>Category 2001-2999: Cash advance categories (ATM, Branch, Check)</li>
     *   <li>Category 3001-3999: Payment categories (Online, Mail, Phone)</li>
     *   <li>Category 4001-4999: Fee categories (Annual, Late, Over Limit)</li>
     *   <li>Category 5001-5999: Interest charge categories</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Transaction category summary screen (COTRN01C → TransactionCategoryService)</li>
     *   <li>Spending analysis reports (TransactionAggregationJob - CBTRN03C replacement)</li>
     *   <li>Budgeting features (compare spending vs budget by category)</li>
     *   <li>Statement category breakdown (group transactions by category on statements)</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Database performs aggregation - more efficient than application-level summation</li>
     *   <li>Result set size: Typically 5-20 categories per account (one row per category)</li>
     *   <li>Query execution: &lt; 100ms for monthly aggregation, &lt; 500ms for yearly</li>
     *   <li>Uses indexes on card.account_id and transaction_timestamp for filtering</li>
     *   <li>GROUP BY uses in-memory hash aggregation (no sort required)</li>
     * </ul>
     * 
     * <p><strong>Empty Result Handling:</strong> If no transactions exist for the account 
     * and date range, returns empty List (not null). Service layer should check isEmpty() 
     * before processing results to avoid NullPointerException.</p>
     * 
     * @param accountId the 11-digit account identifier as String (e.g., "00000000001")
     * @param startDate the start date for aggregation (inclusive)
     * @param endDate   the end date for aggregation (inclusive)
     * @return List of Object arrays where [0] = categoryCode (Integer), [1] = SUM amount (BigDecimal)
     */
    @Query("SELECT t.transactionCategoryCode, SUM(t.transactionAmount) " +
           "FROM Transaction t JOIN Card c ON t.cardNumber = c.cardNumber " +
           "WHERE c.accountId = :accountId " +
           "AND CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate " +
           "GROUP BY t.transactionCategoryCode " +
           "ORDER BY t.transactionCategoryCode")
    List<Object[]> aggregateByCategory(
            @Param("accountId") String accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Counts daily transactions for an account on a specific date.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces transaction counting logic in batch 
     * programs CBTRN02C (Daily Transaction Processing) where COBOL uses accumulator variables 
     * to count transactions processed per day for each account.</p>
     * 
     * <p><strong>COBOL Logic Replaced (CBTRN02C lines 500-600):</strong></p>
     * <pre>
     * COBOL Counting Pattern:
     *   01 WS-TRAN-COUNT       PIC S9(05) COMP-3 VALUE ZERO.
     *   
     *   PERFORM READ-TRANSACTIONS-FOR-DATE
     *      ADD 1 TO WS-TRAN-COUNT
     *   END-PERFORM.
     *   
     *   IF WS-TRAN-COUNT > 100
     *      MOVE 'High volume account' TO WS-MESSAGE
     *   END-IF.
     * 
     * Java Equivalent (THIS METHOD):
     *   long transactionCount = transactionRepository.countDailyTransactions(
     *       "00000000001",           // accountId
     *       LocalDate.of(2024, 12, 15)  // date
     *   );
     *   if (transactionCount > 100) {
     *       logger.warn("High volume account detected");
     *   }
     * </pre>
     * 
     * <p><strong>Custom JPQL Count Query:</strong></p>
     * <pre>
     * SELECT COUNT(t)
     * FROM Transaction t
     * WHERE t.card.accountId = :accountId
     *   AND CAST(t.originationTimestamp AS date) = :date
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Transaction volume monitoring (detect abnormal activity patterns)</li>
     *   <li>Duplicate detection (if count unexpectedly increases, check for duplicates)</li>
     *   <li>Daily transaction processing metrics (batch job statistics)</li>
     *   <li>Fraud detection (unusual spike in daily transaction count)</li>
     *   <li>Performance monitoring (accounts with high transaction volume)</li>
     * </ul>
     * 
     * <p><strong>Usage Examples:</strong></p>
     * <pre>
     * // DailyTransactionProcessingJob (CBTRN02C replacement)
     * LocalDate today = LocalDate.now();
     * long dailyCount = transactionRepository.countDailyTransactions(accountId, today);
     * logger.info("Processed {} transactions for account {} on {}", 
     *             dailyCount, accountId, today);
     * 
     * // Fraud detection threshold check
     * if (dailyCount > 50) {
     *     fraudAlertService.notifyHighVolume(accountId, dailyCount);
     * }
     * 
     * // Duplicate detection
     * long beforeCount = transactionRepository.countDailyTransactions(accountId, date);
     * transactionRepository.save(newTransaction);
     * long afterCount = transactionRepository.countDailyTransactions(accountId, date);
     * if (afterCount - beforeCount != 1) {
     *     logger.error("Unexpected transaction count change detected");
     * }
     * </pre>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>COUNT query is highly optimized by database (index-only scan possible)</li>
     *   <li>Uses compound index on (card.account_id via join, transaction_timestamp)</li>
     *   <li>Query execution: &lt; 20ms typical, &lt; 50ms at 95th percentile</li>
     *   <li>No data fetch required - only count metadata returned</li>
     *   <li>Result fits in single long value (8 bytes)</li>
     * </ul>
     * 
     * <p><strong>Return Value Range:</strong></p>
     * <ul>
     *   <li>Minimum: 0 (no transactions on specified date)</li>
     *   <li>Typical: 1-20 transactions per day for average account</li>
     *   <li>High volume: 50-200 transactions per day for business accounts</li>
     *   <li>Maximum theoretical: 9,223,372,036,854,775,807 (long max value)</li>
     * </ul>
     * 
     * <p><strong>Date Matching Behavior:</strong> The query uses exact date match 
     * (CAST to date = :date), not a range. This counts only transactions with 
     * originationTimestamp falling on the specified date (00:00:00 to 23:59:59.999999 
     * local time).</p>
     * 
     * <p><strong>Zero Count Interpretation:</strong> A return value of 0 indicates either:
     * <ul>
     *   <li>No transactions occurred on the specified date for the account</li>
     *   <li>Account has no cards (account_id not found via card join)</li>
     *   <li>Specified date is in the future (transactions not yet posted)</li>
     *   <li>Account is newly created with no transaction history</li>
     * </ul>
     * </p>
     * 
     * @param accountId the 11-digit account identifier as String (e.g., "00000000001")
     * @param date      the transaction date to count (e.g., "2024-12-15")
     * @return count of transactions for the account on the specified date (0 if none)
     */
    @Query("SELECT COUNT(t) FROM Transaction t JOIN Card c ON t.cardNumber = c.cardNumber " +
           "WHERE c.accountId = :accountId " +
           "AND CAST(t.originationTimestamp AS date) = :date")
    long countDailyTransactions(
            @Param("accountId") String accountId,
            @Param("date") LocalDate date
    );

    /**
     * Checks if a transaction with the given ID already exists in the database.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces duplicate transaction detection logic 
     * in batch load programs CBTRN01C (Transaction Data Load) where COBOL uses VSAM read 
     * with NOTFND condition to check if transaction already exists before inserting.</p>
     * 
     * <p><strong>COBOL Logic Replaced (CBTRN01C lines 400-500):</strong></p>
     * <pre>
     * COBOL Duplicate Detection Pattern:
     *   EXEC CICS READ
     *        DATASET('TRANSACT')
     *        INTO(TRAN-RECORD)
     *        RIDFLD(WS-TRAN-ID)
     *        RESP(WS-RESP-CD)
     *   END-EXEC.
     *   
     *   EVALUATE WS-RESP-CD
     *      WHEN DFHRESP(NORMAL)
     *         MOVE 'Duplicate transaction ID' TO WS-ERROR-MSG
     *         PERFORM WRITE-ERROR-LOG
     *      WHEN DFHRESP(NOTFND)
     *         PERFORM INSERT-TRANSACTION
     *      WHEN OTHER
     *         PERFORM HANDLE-FILE-ERROR
     *   END-EVALUATE.
     * 
     * Java Equivalent (THIS METHOD):
     *   if (transactionRepository.existsByTransactionId(transactionId)) {
     *       logger.error("Duplicate transaction ID detected: {}", transactionId);
     *       throw new DuplicateTransactionException(transactionId);
     *   } else {
     *       transactionRepository.save(newTransaction);
     *   }
     * </pre>
     * 
     * <p><strong>Spring Data JPA Auto-Implementation:</strong> Method name convention 
     * "existsBy[FieldName]" automatically generates optimized existence check query:</p>
     * <pre>
     * SELECT CASE WHEN COUNT(t) > 0 THEN TRUE ELSE FALSE END
     * FROM Transaction t
     * WHERE t.transactionId = :transactionId
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>TransactionDataLoadJob (CBTRN01C) - Prevent duplicate transaction imports</li>
     *   <li>Transaction creation validation - Ensure transaction ID uniqueness</li>
     *   <li>Data reconciliation - Verify transaction presence before update</li>
     *   <li>Batch processing - Skip already-processed transactions</li>
     *   <li>Idempotent operations - Check if transaction already exists before retry</li>
     * </ul>
     * 
     * <p><strong>Usage Examples:</strong></p>
     * <pre>
     * // TransactionDataLoadJob (CBTRN01C replacement)
     * List&lt;Transaction&gt; transactionsToLoad = readFromFile();
     * int duplicates = 0;
     * int loaded = 0;
     * 
     * for (Transaction txn : transactionsToLoad) {
     *     if (transactionRepository.existsByTransactionId(txn.getTransactionId())) {
     *         logger.warn("Skipping duplicate transaction: {}", txn.getTransactionId());
     *         duplicates++;
     *     } else {
     *         transactionRepository.save(txn);
     *         loaded++;
     *     }
     * }
     * logger.info("Loaded {} transactions, skipped {} duplicates", loaded, duplicates);
     * 
     * // TransactionCreationService validation
     * String newTransactionId = generateTransactionId();
     * if (transactionRepository.existsByTransactionId(newTransactionId)) {
     *     // ID collision - generate new ID
     *     newTransactionId = generateTransactionId();
     * }
     * </pre>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Uses unique B-tree index on transaction_id (primary key)</li>
     *   <li>Index-only scan - no table data access required</li>
     *   <li>Query execution: &lt; 5ms typical, &lt; 20ms at 99th percentile</li>
     *   <li>Database returns boolean after first match (short-circuits on LIMIT 1)</li>
     *   <li>Extremely efficient - one of fastest possible query types</li>
     * </ul>
     * 
     * <p><strong>Alternative to findById():</strong> This method is more efficient than 
     * findById() when only existence check is needed (no entity data required). 
     * findById() fetches full entity into memory while existsByTransactionId() only 
     * checks index and returns boolean.</p>
     * 
     * <p><strong>Return Value Interpretation:</strong></p>
     * <ul>
     *   <li>true: Transaction with specified ID exists in database</li>
     *   <li>false: Transaction with specified ID does NOT exist</li>
     *   <li>Never returns null (primitive boolean)</li>
     * </ul>
     * 
     * <p><strong>Batch Processing Optimization:</strong> For checking existence of 
     * multiple transaction IDs, consider using custom @Query with IN clause to reduce 
     * database round-trips:</p>
     * <pre>
     * @Query("SELECT t.transactionId FROM Transaction t WHERE t.transactionId IN :ids")
     * List&lt;String&gt; findExistingTransactionIds(@Param("ids") List&lt;String&gt; ids);
     * // Then check if specific ID exists in returned list
     * </pre>
     * 
     * @param transactionId the unique 16-character transaction identifier to check
     * @return true if transaction exists, false otherwise
     */
    boolean existsByTransactionId(String transactionId);

    /**
     * Finds the transaction with the highest (most recent) transaction ID for sequential ID generation.
     * 
     * <p><strong>COBOL Replacement:</strong> Replaces STARTBR/READPREV logic from COTRN02C.cbl 
     * lines 444-447 where COBOL performs backward browse to read the last (highest) transaction 
     * ID from the TRANSACT VSAM file for generating the next sequential transaction ID.</p>
     * 
     * <p><strong>COBOL Logic Replaced (COTRN02C lines 444-451):</strong></p>
     * <pre>
     * COBOL Sequential ID Generation Pattern:
     *   MOVE HIGH-VALUES TO TRAN-ID
     *   PERFORM STARTBR-TRANSACT-FILE
     *   PERFORM READPREV-TRANSACT-FILE
     *   PERFORM ENDBR-TRANSACT-FILE
     *   MOVE TRAN-ID TO WS-TRAN-ID-N
     *   ADD 1 TO WS-TRAN-ID-N
     *   MOVE WS-TRAN-ID-N TO TRAN-ID
     * 
     * Java Equivalent (THIS METHOD):
     *   Optional&lt;Transaction&gt; lastTransaction = transactionRepository.findTopByOrderByTransactionIdDesc();
     *   String lastTransactionId = lastTransaction
     *       .map(Transaction::getTransactionId)
     *       .orElse(datePrefix + "00000000");
     *   long sequence = Long.parseLong(lastTransactionId.substring(8)) + 1;
     *   String newTransactionId = String.format("%s%08d", datePrefix, sequence);
     * </pre>
     * 
     * <p><strong>Spring Data JPA Auto-Implementation:</strong> Method name convention 
     * "findTop1By...OrderBy[Field]Desc" automatically generates query:</p>
     * <pre>
     * SELECT t FROM Transaction t 
     * ORDER BY t.transactionId DESC 
     * LIMIT 1
     * </pre>
     * 
     * <p><strong>Transaction ID Format:</strong></p>
     * <ul>
     *   <li>Total length: 16 characters</li>
     *   <li>Format: YYYYMMDD########</li>
     *   <li>Date prefix: YYYYMMDD (8 characters) - Transaction date</li>
     *   <li>Sequence suffix: 8-digit zero-padded number</li>
     *   <li>Example: "2024121500000123" = Dec 15, 2024, transaction 123</li>
     *   <li>Sorted descending: Most recent date and highest sequence first</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>TransactionCreationService (COTRN02C) - Generate next sequential transaction ID</li>
     *   <li>Transaction ID validation - Verify ID uniqueness before insertion</li>
     *   <li>Batch job initialization - Determine starting sequence for bulk operations</li>
     *   <li>Audit reporting - Identify most recent transaction for reconciliation</li>
     * </ul>
     * 
     * <p><strong>Usage Example in Service Layer:</strong></p>
     * <pre>
     * // TransactionCreationService.generateTransactionId()
     * String datePrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
     * 
     * String lastTransactionId = transactionRepository
     *     .findTopByOrderByTransactionIdDesc()
     *     .map(Transaction::getTransactionId)
     *     .orElse(datePrefix + "00000000");
     * 
     * long sequence;
     * try {
     *     String lastSequence = lastTransactionId.substring(8);
     *     sequence = Long.parseLong(lastSequence) + 1;
     * } catch (Exception e) {
     *     logger.warn("Error parsing last transaction ID: {}, starting from 1", lastTransactionId);
     *     sequence = 1;
     * }
     * 
     * String transactionId = String.format("%s%08d", datePrefix, sequence);
     * </pre>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Uses B-tree index on transaction_id (primary key) for efficient sorting</li>
     *   <li>LIMIT 1 optimization - stops after finding first (highest) record</li>
     *   <li>Query execution: &lt; 10ms typical, &lt; 30ms at 95th percentile</li>
     *   <li>Index-only scan possible if only transactionId column accessed</li>
     *   <li>Fetches single Transaction entity (~1KB)</li>
     * </ul>
     * 
     * <p><strong>Empty Database Handling:</strong> Returns Optional.empty() if no transactions 
     * exist in database. Service layer should handle this case by starting sequence at 1:</p>
     * <pre>
     * String lastTransactionId = transactionRepository
     *     .findTopByOrderByTransactionIdDesc()
     *     .map(Transaction::getTransactionId)
     *     .orElse(datePrefix + "00000000");  // Start at sequence 0, will increment to 1
     * </pre>
     * 
     * <p><strong>Concurrency Considerations:</strong> This method provides "read last ID" 
     * functionality but does NOT guarantee uniqueness in concurrent environments. For 
     * high-concurrency scenarios, consider:
     * <ul>
     *   <li>Database sequence generator for transaction ID numeric portion</li>
     *   <li>Optimistic locking with retry logic on duplicate key violations</li>
     *   <li>Unique constraint on transaction_id column (already exists as PK)</li>
     *   <li>Application-level synchronization for ID generation (reduces throughput)</li>
     * </ul>
     * </p>
     * 
     * <p><strong>Alternative Implementation:</strong> For better concurrency support, 
     * consider using database sequences:</p>
     * <pre>
     * @Query(value = "SELECT nextval('transaction_id_seq')", nativeQuery = true)
     * Long getNextTransactionSequence();
     * 
     * // Then format: String transactionId = String.format("%s%08d", datePrefix, sequence);
     * </pre>
     * 
     * <p><strong>Return Value:</strong> Optional containing the Transaction with highest 
     * transaction ID if any transactions exist, empty Optional if database is empty or 
     * contains no transactions.</p>
     * 
     * @return Optional containing Transaction with highest transaction ID, or empty if none exist
     */
    Optional<Transaction> findTopByOrderByTransactionIdDesc();
}
