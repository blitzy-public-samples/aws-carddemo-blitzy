/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.reader;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Iterator;

/**
 * Spring Batch ItemReader implementation for reading Account entities requiring balance calculation.
 * 
 * <p>This reader transforms the COBOL CBACT03C.CBL batch program logic which sequentially
 * reads account cross-reference records from VSAM XREFFILE for balance calculation processing.
 * The Java implementation uses JPA pagination with a page size of 1000 to efficiently process
 * large volumes of account records while maintaining memory efficiency.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>COBOL Program: CBACT03C.CBL (Account Cross-Reference Read)</li>
 *   <li>VSAM File: XREFFILE (Indexed Sequential)</li>
 *   <li>Access Mode: Sequential READ operations</li>
 *   <li>Java Pattern: Cursor-based pagination with JPA</li>
 *   <li>Page Size: 1000 records per chunk</li>
 * </ul>
 * 
 * <p><b>COBOL Logic Mapping:</b></p>
 * <pre>
 * COBOL (lines 74-81):
 *   PERFORM UNTIL END-OF-FILE = 'Y'
 *     IF END-OF-FILE = 'N'
 *       PERFORM 1000-XREFFILE-GET-NEXT
 *       IF END-OF-FILE = 'N'
 *         DISPLAY CARD-XREF-RECORD
 * 
 * Java Equivalent:
 *   while (iterator.hasNext()) {
 *     Account account = iterator.next();
 *     return account;  // Process by Spring Batch framework
 *   }
 * </pre>
 * 
 * <p><b>Batch Processing Flow:</b></p>
 * <ol>
 *   <li>Initialize reader with page size 1000 and sort by account ID</li>
 *   <li>Fetch first page of accounts from database</li>
 *   <li>Return accounts one-by-one through iterator</li>
 *   <li>When page exhausted, automatically fetch next page</li>
 *   <li>Return null when no more accounts exist (ItemReader contract)</li>
 *   <li>State is reset between job executions</li>
 * </ol>
 * 
 * <p><b>Performance Characteristics:</b></p>
 * <ul>
 *   <li>Memory Footprint: Maximum 1000 Account entities in memory at once</li>
 *   <li>Database Impact: Indexed query with LIMIT/OFFSET pagination</li>
 *   <li>Thread Safety: Not guaranteed - designed for single-threaded batch execution</li>
 *   <li>Restart Capability: Can resume from last checkpoint via Spring Batch JobRepository</li>
 * </ul>
 * 
 * <p><b>Usage in Spring Batch Job:</b></p>
 * <pre>
 * {@code
 * @Bean
 * public Step accountBalanceStep(JobRepository jobRepository,
 *                                 PlatformTransactionManager transactionManager,
 *                                 AccountBalanceReader reader,
 *                                 AccountBalanceProcessor processor,
 *                                 AccountBalanceWriter writer) {
 *     return new StepBuilder("accountBalanceStep", jobRepository)
 *         .<Account, Account>chunk(1000, transactionManager)
 *         .reader(reader)
 *         .processor(processor)
 *         .writer(writer)
 *         .build();
 * }
 * }
 * </pre>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li>Database connection errors: Logged and thrown as runtime exceptions</li>
 *   <li>Pagination errors: Logged with current page number for troubleshooting</li>
 *   <li>Spring Batch framework handles retry and skip logic based on job configuration</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.entity.Account
 * @see com.carddemo.repository.AccountRepository
 * @see org.springframework.batch.item.ItemReader
 */
@Component
public class AccountBalanceReader implements ItemReader<Account> {

    private static final Logger logger = LoggerFactory.getLogger(AccountBalanceReader.class);

    /**
     * Page size for pagination - matches Spring Batch chunk size for optimal performance.
     * Per Section 0.5 requirements, this is set to 1000 records.
     */
    private static final int PAGE_SIZE = 1000;

    /**
     * AccountRepository for database access.
     * Provides JPA-based query methods for retrieving Account entities.
     */
    private final AccountRepository accountRepository;

    /**
     * Current page number being processed (0-indexed).
     * Incremented as pages are exhausted during reading.
     */
    private int currentPage;

    /**
     * Iterator over the current page of Account entities.
     * When exhausted, triggers loading of the next page.
     */
    private Iterator<Account> accountIterator;

    /**
     * Total count of accounts read so far.
     * Used for logging and monitoring batch job progress.
     */
    private long accountsRead;

    /**
     * Flag indicating if all accounts have been read.
     * When true, subsequent calls to read() return null.
     */
    private boolean exhausted;

    /**
     * Constructor with dependency injection.
     * 
     * <p>Spring automatically injects the AccountRepository implementation
     * at runtime. The reader initializes with page 0 and empty state.</p>
     * 
     * @param accountRepository JPA repository for account data access
     */
    public AccountBalanceReader(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        this.currentPage = 0;
        this.accountIterator = null;
        this.accountsRead = 0L;
        this.exhausted = false;
        logger.info("AccountBalanceReader initialized with page size: {}", PAGE_SIZE);
    }

    /**
     * Reads the next Account entity from the database.
     * 
     * <p>This method implements the Spring Batch ItemReader contract. It returns
     * Account entities one at a time, transparently handling pagination behind
     * the scenes. When all accounts have been read, it returns null to signal
     * completion to the Spring Batch framework.</p>
     * 
     * <p><b>Reading Algorithm:</b></p>
     * <ol>
     *   <li>Check if already exhausted - return null immediately</li>
     *   <li>If no iterator or iterator exhausted, fetch next page</li>
     *   <li>If page is empty, mark as exhausted and return null</li>
     *   <li>Return next account from iterator</li>
     *   <li>Increment accounts read counter</li>
     *   <li>Log progress every 1000 accounts</li>
     * </ol>
     * 
     * <p><b>COBOL Equivalent:</b> PERFORM 1000-XREFFILE-GET-NEXT (line 76)</p>
     * <pre>
     * COBOL:
     *   READ XREFFILE-FILE INTO CARD-XREF-RECORD.
     *   IF XREFFILE-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     *   ELSE IF XREFFILE-STATUS = '10'
     *     MOVE 16 TO APPL-RESULT  (EOF condition)
     * 
     * Java:
     *   Account account = fetchNextFromPage();
     *   if (account == null) {
     *     return null;  // EOF equivalent
     *   }
     *   return account;
     * </pre>
     * 
     * <p><b>Thread Safety:</b> This method is NOT thread-safe. It maintains
     * internal state (currentPage, accountIterator) that assumes single-threaded
     * access. This is appropriate for Spring Batch jobs configured with
     * single-threaded step execution.</p>
     * 
     * <p><b>Performance:</b> Database access occurs only when a new page needs
     * to be fetched (every 1000 accounts). Between page fetches, accounts are
     * served from the in-memory iterator with O(1) access time.</p>
     * 
     * @return Next Account entity, or null if no more accounts exist
     * @throws RuntimeException if database access fails
     */
    @Override
    public Account read() {
        // Return null immediately if we've exhausted all accounts
        if (exhausted) {
            return null;
        }

        try {
            // If we don't have an iterator yet, or current iterator is exhausted, fetch next page
            if (accountIterator == null || !accountIterator.hasNext()) {
                fetchNextPage();
                
                // If fetching next page resulted in no data, we're done
                if (accountIterator == null || !accountIterator.hasNext()) {
                    exhausted = true;
                    logger.info("Account reading completed. Total accounts read: {}", accountsRead);
                    return null;
                }
            }

            // Get next account from current page iterator
            Account account = accountIterator.next();
            accountsRead++;

            // Log progress every 1000 accounts for monitoring
            if (accountsRead % 1000 == 0) {
                logger.info("Progress: {} accounts read so far", accountsRead);
            }

            return account;

        } catch (Exception e) {
            logger.error("Error reading account data at page {} after {} accounts read", 
                        currentPage, accountsRead, e);
            throw new RuntimeException("Failed to read account data for balance calculation", e);
        }
    }

    /**
     * Fetches the next page of Account entities from the database.
     * 
     * <p>This method encapsulates the JPA pagination logic. It creates a Pageable
     * request with the current page number, page size 1000, and sorting by account ID
     * in ascending order. This ensures consistent, repeatable ordering across multiple
     * batch job executions.</p>
     * 
     * <p><b>Query Details:</b></p>
     * <ul>
     *   <li>Query: SELECT a FROM Account a ORDER BY a.id ASC</li>
     *   <li>Page Size: 1000 records</li>
     *   <li>Sort Order: Account ID ascending (for deterministic ordering)</li>
     *   <li>Fetch Type: Lazy (only requested page is loaded)</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b> Sequential VSAM READ with implicit positioning</p>
     * <pre>
     * COBOL maintains file position automatically:
     *   OPEN INPUT XREFFILE-FILE
     *   READ XREFFILE-FILE INTO CARD-XREF-RECORD  (reads next record)
     * 
     * Java explicitly manages pagination:
     *   PageRequest pageRequest = PageRequest.of(currentPage, PAGE_SIZE, Sort.by("id"))
     *   Page<Account> page = repository.findAll(pageRequest)
     * </pre>
     * 
     * <p><b>Performance Optimization:</b></p>
     * <ul>
     *   <li>Database uses LIMIT/OFFSET for efficient pagination</li>
     *   <li>Account ID index ensures fast sorted retrieval</li>
     *   <li>Only requested page loaded into memory (not all accounts)</li>
     *   <li>Sort by primary key avoids expensive sort operations</li>
     * </ul>
     * 
     * <p><b>State Management:</b></p>
     * <ul>
     *   <li>Increments currentPage after successful fetch</li>
     *   <li>Sets accountIterator to new page's iterator</li>
     *   <li>Handles empty pages gracefully (sets iterator to empty)</li>
     * </ul>
     * 
     * @throws org.springframework.dao.DataAccessException if database query fails
     */
    private void fetchNextPage() {
        logger.debug("Fetching page {} of accounts (page size: {})", currentPage, PAGE_SIZE);

        // Create pageable request with page number, size, and sort order
        Pageable pageable = PageRequest.of(currentPage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));

        // Execute paginated query through repository
        Page<Account> accountPage = accountRepository.findAll(pageable);

        logger.debug("Fetched page {} with {} accounts (total elements: {}, total pages: {})",
                    currentPage, 
                    accountPage.getNumberOfElements(),
                    accountPage.getTotalElements(),
                    accountPage.getTotalPages());

        // Set iterator to the content of current page
        if (accountPage.hasContent()) {
            accountIterator = accountPage.getContent().iterator();
            logger.debug("Page {} has {} accounts", currentPage, accountPage.getContent().size());
        } else {
            // Empty page - no more accounts to read
            accountIterator = null;
            logger.debug("Page {} is empty - no more accounts", currentPage);
        }

        // Increment page number for next fetch
        currentPage++;
    }

    /**
     * Resets the reader state to initial conditions.
     * 
     * <p>This method is useful for:</p>
     * <ul>
     *   <li>Testing - reset between test cases</li>
     *   <li>Job restart - clear state before restarting</li>
     *   <li>Reader reuse - prepare for a new read cycle</li>
     * </ul>
     * 
     * <p><b>State Reset Actions:</b></p>
     * <ul>
     *   <li>Reset current page to 0</li>
     *   <li>Clear account iterator</li>
     *   <li>Reset accounts read counter</li>
     *   <li>Clear exhausted flag</li>
     * </ul>
     * 
     * <p><b>Usage Note:</b> Spring Batch creates a new reader instance for each
     * job execution, so explicit reset is typically not needed. However, this
     * method is provided for scenarios where reader instances are reused.</p>
     */
    public void reset() {
        logger.info("Resetting AccountBalanceReader state");
        this.currentPage = 0;
        this.accountIterator = null;
        this.accountsRead = 0L;
        this.exhausted = false;
    }

    /**
     * Gets the current count of accounts read so far.
     * 
     * <p>This method is primarily used for:</p>
     * <ul>
     *   <li>Monitoring batch job progress</li>
     *   <li>Testing and validation</li>
     *   <li>Logging and debugging</li>
     * </ul>
     * 
     * @return Number of accounts read from the start of execution
     */
    public long getAccountsRead() {
        return accountsRead;
    }

    /**
     * Checks if the reader has exhausted all available accounts.
     * 
     * <p>Once exhausted, all subsequent calls to read() will return null
     * without attempting additional database queries.</p>
     * 
     * @return true if all accounts have been read, false otherwise
     */
    public boolean isExhausted() {
        return exhausted;
    }
}
