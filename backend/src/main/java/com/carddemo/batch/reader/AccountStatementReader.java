/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.reader;

import com.carddemo.batch.processor.StatementProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;

/**
 * Spring Batch ItemReader for reading accounts requiring statement generation.
 * 
 * <p>This reader transforms the COBOL CBSTM03A.CBL sequential XREF file read logic
 * into a cursor-based paginated database query. It replaces the mainframe approach
 * of reading XREFFILE sequentially with direct account queries against PostgreSQL.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>COBOL: READ XREFFILE sequential → Paginated database query with cursor</li>
 *   <li>COBOL: File-status '10' (EOF) → return null from read()</li>
 *   <li>COBOL: ACCT-ACTIVE-STATUS check → WHERE clause in SQL query</li>
 *   <li>COBOL: PERFORM UNTIL END-OF-FILE → Spring Batch chunk processing</li>
 * </ul>
 * 
 * <p><b>Functional Requirements:</b></p>
 * <ul>
 *   <li>Query active accounts with transactions in statement period</li>
 *   <li>Support configurable fetch size (default 1000 per Section 0.5)</li>
 *   <li>Implement restart capability via ExecutionContext state persistence</li>
 *   <li>Cursor-based pagination to avoid loading all accounts into memory</li>
 * </ul>
 * 
 * <p><b>Performance Characteristics:</b></p>
 * <ul>
 *   <li>Fetch size: 1000 records (matches chunk size per Section 0.5)</li>
 *   <li>Pagination: Cursor-based to minimize memory footprint</li>
 *   <li>Query optimization: Single query with joins to avoid N+1 problems</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component("accountStatementReader")
@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class AccountStatementReader extends AbstractItemCountingItemStreamItemReader<StatementProcessor.StatementInput> {

    private static final Logger logger = LoggerFactory.getLogger(AccountStatementReader.class);
    
    /**
     * Default fetch size for pagination - matches chunk size per Section 0.5
     */
    private static final int DEFAULT_FETCH_SIZE = 1000;
    
    /**
     * ExecutionContext key for tracking last processed account ID
     */
    private static final String LAST_ACCOUNT_ID_KEY = "last.account.id";
    
    /**
     * ExecutionContext key for tracking current page number
     */
    private static final String CURRENT_PAGE_KEY = "current.page.number";
    
    /**
     * ExecutionContext key for tracking total accounts read
     */
    private static final String TOTAL_ACCOUNTS_KEY = "total.accounts.read";
    
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final LocalDate statementPeriodStart;
    private final LocalDate statementPeriodEnd;
    private final int fetchSize;
    
    /**
     * Iterator for current page of accounts
     */
    private Iterator<Account> accountIterator;
    
    /**
     * Current page number in pagination sequence
     */
    private int currentPage;
    
    /**
     * Flag indicating if more pages are available
     */
    private boolean hasNextPage;
    
    /**
     * Last processed account ID for restart capability
     */
    private Long lastAccountId;
    
    /**
     * Total number of accounts read in this execution
     */
    private long totalAccountsRead;
    
    /**
     * Constructs an AccountStatementReader with required dependencies.
     * 
     * <p>This constructor uses Spring's dependency injection to obtain the
     * AccountRepository and job parameters for the statement period.</p>
     * 
     * @param accountRepository Repository for account data access
     * @param customerRepository Repository for customer data access
     * @param transactionRepository Repository for transaction data access
     * @param statementPeriodStart Start date of statement period (from job parameters)
     * @param statementPeriodEnd End date of statement period (from job parameters)
     */
    @Autowired
    public AccountStatementReader(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            TransactionRepository transactionRepository,
            @Value("#{jobParameters['statementPeriodStart']}") LocalDate statementPeriodStart,
            @Value("#{jobParameters['statementPeriodEnd']}") LocalDate statementPeriodEnd) {
        this(accountRepository, customerRepository, transactionRepository, statementPeriodStart, statementPeriodEnd, DEFAULT_FETCH_SIZE);
    }
    
    /**
     * Constructs an AccountStatementReader with custom fetch size.
     * 
     * <p>This constructor allows customization of the page size for pagination,
     * useful for performance tuning based on database characteristics.</p>
     * 
     * @param accountRepository Repository for account data access
     * @param customerRepository Repository for customer data access
     * @param transactionRepository Repository for transaction data access
     * @param statementPeriodStart Start date of statement period
     * @param statementPeriodEnd End date of statement period
     * @param fetchSize Number of records to fetch per page
     */
    public AccountStatementReader(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            TransactionRepository transactionRepository,
            LocalDate statementPeriodStart,
            LocalDate statementPeriodEnd,
            int fetchSize) {
        super();
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.statementPeriodStart = statementPeriodStart;
        this.statementPeriodEnd = statementPeriodEnd;
        this.fetchSize = fetchSize;
        this.currentPage = 0;
        this.hasNextPage = true;
        this.totalAccountsRead = 0L;
        
        // Set the name for this reader (required by AbstractItemCountingItemStreamItemReader)
        setName("accountStatementReader");
        
        logger.info("AccountStatementReader initialized with period {} to {}, fetch size: {}",
                statementPeriodStart, statementPeriodEnd, fetchSize);
    }
    
    /**
     * Opens the reader and initializes the database cursor for account pagination.
     * 
     * <p>This method is called by Spring Batch before reading begins. It performs
     * the following operations:</p>
     * <ol>
     *   <li>Initializes the first page of accounts from the database</li>
     *   <li>Sets up the iterator for the current page</li>
     *   <li>Logs statement generation start metrics</li>
     * </ol>
     * 
     * <p><b>COBOL Equivalent:</b> OPEN XREFFILE (line 765-777 in CBSTM03A.CBL)</p>
     * 
     * @throws ItemStreamException if database access fails
     */
    @Override
    protected void doOpen() throws ItemStreamException {
        try {
            logger.info("Opening AccountStatementReader for statement period {} to {}",
                    statementPeriodStart, statementPeriodEnd);
            
            // Only reset state if not restarting (lastAccountId would have been set by open())
            if (lastAccountId == null) {
                currentPage = 0;
                totalAccountsRead = 0L;
                logger.info("Starting fresh statement generation run");
            } else {
                logger.info("Continuing statement generation from restart point");
            }
            
            // Load first page of accounts
            loadNextPage();
            
            logger.info("AccountStatementReader opened successfully");
            
        } catch (Exception e) {
            logger.error("Error opening AccountStatementReader", e);
            throw new ItemStreamException("Failed to open AccountStatementReader", e);
        }
    }
    
    /**
     * Reads the next account requiring statement generation.
     * 
     * <p>This method implements the core reading logic with cursor-based pagination.
     * It returns accounts one at a time until all accounts have been processed.</p>
     * 
     * <p><b>Reading Strategy:</b></p>
     * <ol>
     *   <li>Return next account from current page iterator</li>
     *   <li>If current page exhausted and more pages exist, load next page</li>
     *   <li>If no more accounts exist, return null to signal end of data</li>
     * </ol>
     * 
     * <p><b>COBOL Equivalent:</b> READ XREFFILE sequential (line 345-366 in CBSTM03A.CBL)</p>
     * <p><b>EOF Handling:</b> File-status '10' → return null</p>
     * 
     * @return Next Account requiring statement generation, or null if no more accounts
     * @throws Exception if database access fails
     */
    @Override
    protected StatementProcessor.StatementInput doRead() throws Exception {
        // Check if current iterator has more items
        if (accountIterator != null && accountIterator.hasNext()) {
            Account account = accountIterator.next();
            lastAccountId = account.getAccountId();
            totalAccountsRead++;
            
            if (logger.isDebugEnabled()) {
                logger.debug("Read account {} for statement generation (total read: {})",
                        account.getAccountId(), totalAccountsRead);
            }
            
            // Get customer from account relationship
            Customer customer = account.getCustomer();
            if (customer == null) {
                throw new RuntimeException(
                        "Customer not found for account " + account.getAccountId());
            }
            
            // Fetch transactions for statement period
            // Use unpaged query to get all transactions for the statement period
            Pageable unpaged = Pageable.unpaged();
            Page<Transaction> transactionPage = transactionRepository
                    .findByAccountIdAndTransactionDateBetween(
                            account.getAccountId(),
                            statementPeriodStart,
                            statementPeriodEnd,
                            unpaged);
            List<Transaction> transactions = transactionPage.getContent();
            
            // Statement date is typically the end date of the period
            LocalDate statementDate = statementPeriodEnd;
            
            // Construct and return StatementInput with all 6 required parameters
            return new StatementProcessor.StatementInput(
                    account,
                    customer,
                    transactions,
                    statementDate,
                    statementPeriodStart,
                    statementPeriodEnd);
        }
        
        // Current page exhausted - try to load next page if available
        if (hasNextPage) {
            currentPage++;
            loadNextPage();
            
            // After loading next page, recursively call doRead()
            if (accountIterator != null && accountIterator.hasNext()) {
                return doRead();
            }
        }
        
        // No more accounts available - signal end of data (equivalent to COBOL EOF)
        logger.info("Reached end of accounts. Total accounts read: {}", totalAccountsRead);
        return null;
    }
    
    /**
     * Closes the reader and releases database resources.
     * 
     * <p>This method is called by Spring Batch after reading completes or if an error
     * occurs. It performs cleanup operations and logs final statistics.</p>
     * 
     * <p><b>COBOL Equivalent:</b> CLOSE XREFFILE (line 873-887 in CBSTM03A.CBL)</p>
     * 
     * @throws ItemStreamException if cleanup fails
     */
    @Override
    protected void doClose() throws ItemStreamException {
        try {
            logger.info("Closing AccountStatementReader. Total accounts processed: {}",
                    totalAccountsRead);
            
            // Clear iterator to release memory
            accountIterator = null;
            
            // Reset pagination state
            currentPage = 0;
            hasNextPage = false;
            
            logger.info("AccountStatementReader closed successfully");
            
        } catch (Exception e) {
            logger.error("Error closing AccountStatementReader", e);
            throw new ItemStreamException("Failed to close AccountStatementReader", e);
        }
    }
    
    /**
     * Updates the ExecutionContext with current reader state for restart capability.
     * 
     * <p>This method is called periodically by Spring Batch (typically after each chunk
     * commit) to save the current position. If the job fails or is stopped, it can
     * resume from this saved position.</p>
     * 
     * <p><b>Restart Strategy:</b></p>
     * <ul>
     *   <li>Save last processed account ID</li>
     *   <li>Save current page number</li>
     *   <li>Save total accounts read count</li>
     * </ul>
     * 
     * @param executionContext Spring Batch execution context for state persistence
     * @throws ItemStreamException if state update fails
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Call parent implementation first
        super.update(executionContext);
        
        try {
            if (lastAccountId != null) {
                executionContext.putLong(LAST_ACCOUNT_ID_KEY, lastAccountId);
                executionContext.putInt(CURRENT_PAGE_KEY, currentPage);
                executionContext.putLong(TOTAL_ACCOUNTS_KEY, totalAccountsRead);
                
                if (logger.isDebugEnabled()) {
                    logger.debug("Updated ExecutionContext: lastAccountId={}, currentPage={}, totalRead={}",
                            lastAccountId, currentPage, totalAccountsRead);
                }
            }
        } catch (Exception e) {
            logger.error("Error updating ExecutionContext", e);
            throw new ItemStreamException("Failed to update ExecutionContext", e);
        }
    }
    
    /**
     * Opens the reader with ExecutionContext for restart support.
     * This method handles restart scenario by checking for saved state.
     * 
     * @param executionContext Spring Batch execution context
     * @throws ItemStreamException if open fails
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        // Check for restart scenario before calling super.open()
        if (executionContext.containsKey(LAST_ACCOUNT_ID_KEY)) {
            lastAccountId = executionContext.getLong(LAST_ACCOUNT_ID_KEY);
            currentPage = executionContext.getInt(CURRENT_PAGE_KEY, 0);
            totalAccountsRead = executionContext.getLong(TOTAL_ACCOUNTS_KEY, 0L);
            
            logger.info("Restarting from last account ID: {}, page: {}, total read: {}",
                    lastAccountId, currentPage, totalAccountsRead);
        }
        
        // Call parent implementation which will call doOpen()
        super.open(executionContext);
    }
    
    /**
     * Loads the next page of accounts from the database.
     * 
     * <p>This method performs the actual database query to fetch accounts requiring
     * statement generation. It implements the following selection criteria from the
     * original COBOL logic:</p>
     * 
     * <p><b>Account Selection Criteria (COBOL CBSTM03A.CBL lines 317-329):</b></p>
     * <ul>
     *   <li>Account status = 'A' (Active)</li>
     *   <li>Has transactions in statement period OR has non-zero balance</li>
     *   <li>Not flagged for statement suppression</li>
     * </ul>
     * 
     * <p><b>Query Optimization:</b></p>
     * <ul>
     *   <li>Uses pagination with configurable fetch size (default 1000)</li>
     *   <li>Orders by account ID for consistent pagination</li>
     *   <li>Fetches related customer data to avoid N+1 queries</li>
     *   <li>Supports restart by filtering on lastAccountId</li>
     * </ul>
     * 
     * @throws ItemStreamException if database query fails
     */
    private void loadNextPage() throws ItemStreamException {
        try {
            logger.debug("Loading page {} of accounts (fetch size: {})", currentPage, fetchSize);
            
            // Create pageable with sort by account ID for consistent pagination
            Pageable pageable = PageRequest.of(
                    currentPage,
                    fetchSize,
                    Sort.by(Sort.Direction.ASC, "id")
            );
            
            Page<Account> accountPage;
            
            if (lastAccountId != null && currentPage == 0) {
                // Restart scenario - fetch accounts after the last processed one
                logger.debug("Restart mode: fetching accounts after ID {}", lastAccountId);
                accountPage = accountRepository.findActiveAccountsForStatementPeriodAfterAccountId(
                        statementPeriodEnd,
                        lastAccountId,
                        pageable
                );
            } else {
                // Normal scenario - fetch next page of accounts
                accountPage = accountRepository.findActiveAccountsForStatementPeriod(
                        statementPeriodEnd,
                        pageable
                );
            }
            
            // Update pagination state
            hasNextPage = accountPage.hasNext();
            accountIterator = accountPage.iterator();
            
            logger.debug("Loaded page {} with {} accounts. Has next page: {}",
                    currentPage, accountPage.getNumberOfElements(), hasNextPage);
            
            // Log progress at page boundaries
            if (currentPage % 10 == 0) {
                logger.info("Statement generation progress: page {}, total accounts read: {}",
                        currentPage, totalAccountsRead);
            }
            
        } catch (Exception e) {
            logger.error("Error loading page {} of accounts", currentPage, e);
            throw new ItemStreamException("Failed to load page " + currentPage + " of accounts", e);
        }
    }
    
    /**
     * Gets the total number of accounts read so far.
     * 
     * <p>This method provides visibility into reader progress for monitoring
     * and metrics collection.</p>
     * 
     * @return Total number of accounts read in current execution
     */
    public long getTotalAccountsRead() {
        return totalAccountsRead;
    }
    
    /**
     * Gets the current page number in pagination sequence.
     * 
     * <p>This method provides visibility into pagination progress for monitoring
     * and debugging purposes.</p>
     * 
     * @return Current page number (0-based)
     */
    public int getCurrentPage() {
        return currentPage;
    }
    
    /**
     * Gets the configured fetch size for pagination.
     * 
     * @return Number of records fetched per page
     */
    public int getFetchSize() {
        return fetchSize;
    }
    
    /**
     * Gets the statement period start date.
     * 
     * @return Start date of statement period
     */
    public LocalDate getStatementPeriodStart() {
        return statementPeriodStart;
    }
    
    /**
     * Gets the statement period end date.
     * 
     * @return End date of statement period
     */
    public LocalDate getStatementPeriodEnd() {
        return statementPeriodEnd;
    }
}

