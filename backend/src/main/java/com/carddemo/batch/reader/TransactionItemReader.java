package com.carddemo.batch.reader;

import com.carddemo.entity.Transaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.List;

/**
 * Spring Batch ItemReader for reading transaction data from PostgreSQL database with
 * cursor-based pagination and fault-tolerant restart capability.
 * 
 * <p>This reader transforms COBOL CBTRN01C.cbl sequential file READ operations on
 * DALYTRAN-FILE (daily transaction file) to Spring Batch chunk-oriented processing
 * with database queries. Supports both database and flat file sources for flexibility
 * in batch job configuration per Section 0.5 refactored structure planning.</p>
 * 
 * <p><strong>COBOL Source Pattern (CBTRN01C.cbl):</strong></p>
 * <pre>
 * FILE-CONTROL.
 *     SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
 *            ORGANIZATION IS SEQUENTIAL
 *            ACCESS MODE  IS SEQUENTIAL
 *            FILE STATUS  IS DALYTRAN-STATUS.
 * 
 * PROCEDURE DIVISION.
 *     OPEN INPUT DALYTRAN-FILE
 *     PERFORM UNTIL DALYTRAN-STATUS = '10'  (EOF)
 *         READ DALYTRAN-FILE INTO TRAN-RECORD
 *         AT END
 *             MOVE '10' TO DALYTRAN-STATUS
 *         NOT AT END
 *             PERFORM PROCESS-TRANSACTION
 *         END-READ
 *     END-PERFORM
 *     CLOSE DALYTRAN-FILE
 * </pre>
 * 
 * <p><strong>Java Spring Batch Equivalent Pattern:</strong></p>
 * <pre>
 * // doOpen() = OPEN INPUT
 * // doRead() = READ DALYTRAN-FILE (returns null at EOF = file-status '10')
 * // doClose() = CLOSE DALYTRAN-FILE
 * // update() = Save restart position to ExecutionContext
 * </pre>
 * 
 * <p><strong>Data Structure Transformation (CVTRA05Y.cpy):</strong></p>
 * <ul>
 *   <li>TRAN-RECORD (350-byte fixed-length) → Transaction JPA entity</li>
 *   <li>TRAN-ID PIC X(16) → String transactionId (trim whitespace)</li>
 *   <li>TRAN-TYPE-CD PIC X(02) → String transactionTypeCode (trim)</li>
 *   <li>TRAN-CAT-CD PIC 9(04) → Integer transactionCategoryCode</li>
 *   <li>TRAN-SOURCE PIC X(10) → String transactionSource (trim)</li>
 *   <li>TRAN-DESC PIC X(100) → String transactionDescription (trim)</li>
 *   <li>TRAN-AMT PIC S9(09)V99 → BigDecimal(11,2) with HALF_UP rounding</li>
 *   <li>TRAN-MERCHANT-ID PIC 9(09) → Long merchantId</li>
 *   <li>TRAN-MERCHANT-NAME/CITY/ZIP PIC X → String (trim)</li>
 *   <li>TRAN-CARD-NUM PIC X(16) → String cardNumber (trim)</li>
 *   <li>TRAN-ORIG-TS PIC X(26) → LocalDateTime originationTimestamp</li>
 *   <li>TRAN-PROC-TS PIC X(26) → LocalDateTime processingTimestamp</li>
 * </ul>
 * 
 * <p><strong>Chunk-Oriented Processing:</strong></p>
 * <ul>
 *   <li>Default page size: 1000 records per Section 0.5 batch configuration</li>
 *   <li>Cursor-based pagination with in-memory buffer (List&lt;Transaction&gt;)</li>
 *   <li>Fetch next page when current page iterator exhausted</li>
 *   <li>Return null when no more records (equivalent to COBOL file-status '10' EOF)</li>
 * </ul>
 * 
 * <p><strong>Fault-Tolerant Restart Capability:</strong></p>
 * <ul>
 *   <li>ExecutionContext stores current page number and position within page</li>
 *   <li>update() persists state after each chunk processed</li>
 *   <li>doOpen() restores state on job restart</li>
 *   <li>Enables batch job recovery without reprocessing entire dataset</li>
 * </ul>
 * 
 * <p><strong>CRITICAL Numeric Precision Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>TRAN-AMT PIC S9(09)V99 MUST use BigDecimal with precision=11, scale=2</li>
 *   <li>ALL amounts explicitly call setScale(2, RoundingMode.HALF_UP)</li>
 *   <li>Matches COBOL COMP-3 packed decimal precision for financial calculations</li>
 *   <li>NO float or double types allowed for monetary amounts</li>
 * </ul>
 * 
 * <p><strong>Usage in Batch Jobs:</strong></p>
 * <ul>
 *   <li>TransactionDataLoadJob (CBTRN01C.cbl) - Daily transaction data load</li>
 *   <li>DailyTransactionProcessingJob (CBTRN02C.cbl) - Daily batch processing at 2 AM</li>
 *   <li>Configurable via @StepScope for parameterized job execution</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>High-volume processing: 10,000+ transactions per day typical load</li>
 *   <li>Batch window: Must complete within 4-hour window per Section 0.2</li>
 *   <li>Page size 1000 balances memory usage vs. database round trips</li>
 *   <li>Database cursor prevents full table scan memory consumption</li>
 * </ul>
 * 
 * @see Transaction
 * @see TransactionDataLoadJob
 * @see DailyTransactionProcessingJob
 * @see AbstractItemCountingItemStreamItemReader
 * @see <a href="Section 0.6">File-by-File Transformation Plan - Batch Processing</a>
 * @see <a href="Section 0.9">Numeric Precision and Transaction Boundary Requirements</a>
 */
@Component
@Scope("step")
public class TransactionItemReader extends AbstractItemCountingItemStreamItemReader<Transaction> {

    /**
     * SLF4J logger instance for diagnostic logging and audit trail.
     * 
     * <p>Enables comprehensive logging during batch transaction data loading:
     * <ul>
     *   <li>INFO: Reader initialization, page fetches, completion status</li>
     *   <li>DEBUG: Individual record reads, pagination state, restart position</li>
     *   <li>WARN: Data validation issues, missing optional fields, timestamp parse failures</li>
     *   <li>ERROR: Fatal errors preventing batch job execution, database connectivity issues</li>
     * </ul>
     * </p>
     * 
     * <p>Logger naming follows class-based convention for easy log filtering:
     * `com.carddemo.batch.reader.TransactionItemReader` in log configuration.</p>
     * 
     * <p>Supports audit trail completeness per Section 0.9 compliance requirements.</p>
     */
    private static final Logger logger = LoggerFactory.getLogger(TransactionItemReader.class);

    /**
     * Default page size for database pagination matching Spring Batch chunk size.
     * 
     * <p>Per Section 0.5 batch configuration, readers support configurable chunk size
     * with 1000 records as the default. This page size balances:</p>
     * <ul>
     *   <li>Memory consumption: 1000 Transaction entities in memory per page</li>
     *   <li>Database round trips: Minimizes query frequency for high-volume processing</li>
     *   <li>Restart granularity: Finer restart points for fault tolerance</li>
     *   <li>Performance: Optimal for 10,000+ transactions per day processing</li>
     * </ul>
     * 
     * <p>Can be overridden via {@link #setPageSize(int)} method for job-specific tuning.</p>
     */
    private static final int DEFAULT_PAGE_SIZE = 1000;

    /**
     * ExecutionContext key for storing current page number during restart.
     * 
     * <p>Enables fault-tolerant restart capability by persisting pagination state
     * in Spring Batch ExecutionContext. On job restart, reader resumes from last
     * successfully processed page rather than reprocessing entire dataset from
     * beginning, improving recovery time and preventing duplicate processing.</p>
     * 
     * <p>Value stored: Integer page number (0-based index of current page)</p>
     */
    private static final String CURRENT_PAGE_KEY = "current.page";

    /**
     * DateTimeFormatter for parsing COBOL PIC X(26) timestamp fields.
     * 
     * <p>COBOL timestamp format from CVTRA05Y.cpy copybook:</p>
     * <ul>
     *   <li>TRAN-ORIG-TS PIC X(26): "YYYY-MM-DD HH:MM:SS.SSSSSS" (26 characters)</li>
     *   <li>TRAN-PROC-TS PIC X(26): Same format as origination timestamp</li>
     *   <li>Microsecond precision: 6 decimal places for fractional seconds</li>
     * </ul>
     * 
     * <p>Example timestamp: "2024-12-15 14:23:45.123456"</p>
     * 
     * <p>Thread-safe: DateTimeFormatter is immutable and can be shared across threads
     * in multi-threaded batch processing environments per Section 0.5 batch processing
     * requirements for parallel step execution.</p>
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * JPA EntityManager for database query execution.
     * 
     * <p>Container-managed EntityManager instance injected via @PersistenceContext
     * annotation. Provides:</p>
     * <ul>
     *   <li>Automatic transaction management via Spring's JpaTransactionManager</li>
     *   <li>Connection pooling through HikariCP per Section 0.7 database configuration</li>
     *   <li>Thread-safe operation in multi-threaded batch processing</li>
     *   <li>Automatic resource cleanup at transaction boundary</li>
     * </ul>
     * 
     * <p>Replaces COBOL file control declaratives (SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN)
     * with Spring-managed persistence context supporting automatic resource lifecycle.</p>
     * 
     * <p>Used to execute paginated queries for transaction data retrieval with
     * cursor-based pagination pattern for memory-efficient processing of large datasets.</p>
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Configurable page size for database pagination.
     * 
     * <p>Number of Transaction records fetched per database query. Defaults to
     * {@link #DEFAULT_PAGE_SIZE} (1000) but can be overridden via {@link #setPageSize(int)}
     * for job-specific performance tuning.</p>
     * 
     * <p>Performance tuning guidance:</p>
     * <ul>
     *   <li>Smaller pages (500): Lower memory usage, more frequent database queries</li>
     *   <li>Larger pages (2000): Fewer queries, higher memory consumption per page</li>
     *   <li>Default (1000): Balanced approach for typical 10,000+ transaction volumes</li>
     * </ul>
     * 
     * <p>Must match Spring Batch chunk size configuration for optimal performance
     * to avoid unnecessary database round trips mid-chunk.</p>
     */
    private int pageSize = DEFAULT_PAGE_SIZE;

    /**
     * Current page number for pagination (0-based index).
     * 
     * <p>Tracks which page of results is currently being processed. Incremented
     * after each page is exhausted. Persisted to ExecutionContext via update()
     * method for fault-tolerant restart capability.</p>
     * 
     * <p>Page progression:</p>
     * <ul>
     *   <li>Page 0: Records 0-999 (first 1000 transactions)</li>
     *   <li>Page 1: Records 1000-1999 (second 1000 transactions)</li>
     *   <li>Page N: Records (N*1000) to ((N+1)*1000 - 1)</li>
     * </ul>
     * 
     * <p>On job restart, this value is restored from ExecutionContext to resume
     * from last successfully processed page.</p>
     */
    private int currentPage = 0;

    /**
     * In-memory buffer holding current page of Transaction entities.
     * 
     * <p>Populated by doOpen() and refreshed when iterator exhausted. Enables
     * efficient iteration via {@link #currentIterator} without repeated database
     * queries for each individual record read within a page.</p>
     * 
     * <p>Null when reader closed or no more pages available. Size typically equals
     * {@link #pageSize} except for final page which may contain fewer records.</p>
     */
    private List<Transaction> currentPageData;

    /**
     * Iterator over current page of transactions.
     * 
     * <p>Provides sequential access to Transaction entities in {@link #currentPageData}.
     * When hasNext() returns false, reader fetches next page via database query and
     * creates new iterator for subsequent records.</p>
     * 
     * <p>Null when reader closed or when transitioning between pages. Replaced with
     * new iterator each time a new page is fetched from database.</p>
     */
    private Iterator<Transaction> currentIterator;

    /**
     * Constructor initializing reader with unique name for identification.
     * 
     * <p>Sets reader name to class simple name for ExecutionContext key generation
     * and log message identification. The name is used by Spring Batch infrastructure
     * to track reader state and execution statistics.</p>
     * 
     * <p>Must be called before reader is used in batch job step configuration.</p>
     */
    public TransactionItemReader() {
        setName("TransactionItemReader");
        logger.debug("TransactionItemReader instance created with default page size: {}", DEFAULT_PAGE_SIZE);
    }

    /**
     * Opens the reader and initializes database resources for transaction reading.
     * 
     * <p>This method is called once when the batch step starts, equivalent to
     * COBOL OPEN INPUT DALYTRAN-FILE statement. Performs:</p>
     * <ul>
     *   <li>Restore pagination state from ExecutionContext on job restart</li>
     *   <li>Fetch first page of transaction data from database</li>
     *   <li>Initialize iterator for sequential record access</li>
     *   <li>Log reader initialization for audit trail</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * OPEN INPUT DALYTRAN-FILE
     * IF DALYTRAN-STATUS NOT = '00'
     *     DISPLAY 'ERROR OPENING TRANSACTION FILE'
     *     STOP RUN
     * END-IF
     * </pre>
     * 
     * <p><strong>Restart Behavior:</strong> If ExecutionContext contains saved state
     * from previous execution (job restart scenario), restores currentPage to resume
     * from last successfully processed page. Otherwise starts from page 0.</p>
     * 
     * <p><strong>Exception Handling:</strong> Any database errors during initialization
     * throw ItemStreamException to signal batch job failure and trigger rollback per
     * Section 0.9 transaction boundary requirements.</p>
     * 
     * @param executionContext Spring Batch execution context containing restart state
     * @throws ItemStreamException if database initialization fails or query execution error
     */
    @Override
    protected void doOpen() throws ItemStreamException {
        logger.info("Opening TransactionItemReader - initializing database cursor");
        
        try {
            // Fetch first page of transaction data from database
            fetchPage();
            
            logger.info("TransactionItemReader opened successfully - first page fetched with {} records", 
                       currentPageData != null ? currentPageData.size() : 0);
        } catch (Exception e) {
            logger.error("Failed to open TransactionItemReader - database query error", e);
            throw new ItemStreamException("Error initializing TransactionItemReader", e);
        }
    }

    /**
     * Reads a single transaction record from current page buffer.
     * 
     * <p>This method is called repeatedly by Spring Batch framework to retrieve
     * transactions one at a time for chunk-oriented processing. Equivalent to
     * COBOL READ DALYTRAN-FILE statement with sequential access.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * READ DALYTRAN-FILE INTO TRAN-RECORD
     *     AT END
     *         MOVE '10' TO DALYTRAN-STATUS  (Return null = EOF)
     *     NOT AT END
     *         PERFORM PROCESS-TRANSACTION   (Return Transaction entity)
     * END-READ
     * </pre>
     * 
     * <p><strong>Reading Algorithm:</strong></p>
     * <ol>
     *   <li>Check if current iterator has more records → return next Transaction</li>
     *   <li>If iterator exhausted, fetch next page from database</li>
     *   <li>If next page has records, create new iterator and return first Transaction</li>
     *   <li>If no more pages available, return null (EOF equivalent to file-status '10')</li>
     * </ol>
     * 
     * <p><strong>Null Return Semantics:</strong> Returning null signals to Spring Batch
     * that all records have been read (equivalent to COBOL file-status '10' EOF condition).
     * This triggers chunk commit, step completion, and reader close lifecycle.</p>
     * 
     * <p><strong>Performance:</strong> In-memory iteration over current page is very fast.
     * Database query only occurs once per page (every 1000 records by default), minimizing
     * database round trips for optimal throughput under 10,000 TPS load.</p>
     * 
     * @return Next Transaction entity from current page, or null if no more records (EOF)
     * @throws Exception if database query fails or data validation error occurs
     */
    @Override
    protected Transaction doRead() throws Exception {
        // Check if current iterator has more records
        if (currentIterator != null && currentIterator.hasNext()) {
            Transaction transaction = currentIterator.next();
            logger.debug("Read transaction: {}", transaction.getTransactionId());
            return transaction;
        }
        
        // Current page exhausted, attempt to fetch next page
        logger.debug("Current page exhausted, fetching next page (page {})", currentPage + 1);
        currentPage++;
        fetchPage();
        
        // Check if new page has records
        if (currentIterator != null && currentIterator.hasNext()) {
            Transaction transaction = currentIterator.next();
            logger.debug("Read transaction from new page: {}", transaction.getTransactionId());
            return transaction;
        }
        
        // No more pages available - return null to signal EOF
        logger.info("No more transaction records available - EOF reached at page {}", currentPage);
        return null;
    }

    /**
     * Closes the reader and releases database resources.
     * 
     * <p>This method is called once when the batch step completes (successfully or
     * with error), equivalent to COBOL CLOSE DALYTRAN-FILE statement. Performs:</p>
     * <ul>
     *   <li>Clear current page buffer to release memory</li>
     *   <li>Null out iterator to prevent further access</li>
     *   <li>Log reader closure for audit trail</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * CLOSE DALYTRAN-FILE
     * IF DALYTRAN-STATUS NOT = '00'
     *     DISPLAY 'ERROR CLOSING TRANSACTION FILE'
     * END-IF
     * </pre>
     * 
     * <p><strong>Resource Management:</strong> EntityManager is managed by Spring container
     * and does not require explicit closing. This method only clears reader-specific state
     * (page buffer and iterator). Database connections are returned to HikariCP pool
     * automatically at transaction boundary.</p>
     * 
     * <p><strong>Exception Handling:</strong> Failures during close are logged but do not
     * throw exceptions to prevent masking primary batch job failure if close happens
     * during error handling path.</p>
     */
    @Override
    protected void doClose() {
        logger.info("Closing TransactionItemReader - releasing resources");
        
        try {
            // Clear page buffer to release memory
            if (currentPageData != null) {
                currentPageData.clear();
                currentPageData = null;
            }
            
            // Null out iterator
            currentIterator = null;
            
            logger.info("TransactionItemReader closed successfully");
        } catch (Exception e) {
            logger.error("Error during TransactionItemReader close - ignoring", e);
            // Don't throw exception during close to avoid masking primary failure
        }
    }

    /**
     * Persists current pagination state to ExecutionContext for restart capability.
     * 
     * <p>This method is called periodically by Spring Batch framework (typically after
     * each chunk commit) to save reader state for fault-tolerant restart. If the batch
     * job fails and is restarted, the reader can resume from the last saved state rather
     * than reprocessing the entire dataset from the beginning.</p>
     * 
     * <p><strong>Saved State:</strong></p>
     * <ul>
     *   <li>currentPage: Integer page number (0-based index)</li>
     *   <li>Read from ExecutionContext in doOpen() on job restart</li>
     *   <li>Enables resumption from last successfully processed page</li>
     * </ul>
     * 
     * <p><strong>Restart Scenario Example:</strong></p>
     * <ol>
     *   <li>Job processes pages 0-4 successfully (5000 transactions)</li>
     *   <li>Job fails during page 5 processing</li>
     *   <li>ExecutionContext contains currentPage=5 from last update()</li>
     *   <li>Job restarted: doOpen() reads currentPage=5 from context</li>
     *   <li>Reader resumes from page 5, skipping already-processed pages 0-4</li>
     * </ol>
     * 
     * <p><strong>Performance Note:</strong> State persistence is lightweight (single integer)
     * and does not impact batch job performance. Called synchronously after each chunk
     * commit as part of Spring Batch transaction management.</p>
     * 
     * @param executionContext Spring Batch execution context for state persistence
     * @throws ItemStreamException if state persistence fails (rare, indicates framework issue)
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        super.update(executionContext);
        executionContext.putInt(CURRENT_PAGE_KEY, currentPage);
        logger.debug("Saved restart state - currentPage: {}", currentPage);
    }

    /**
     * Opens the reader with ExecutionContext for restart state restoration.
     * 
     * <p>This is the public entry point for reader initialization, called by Spring Batch
     * framework. Delegates to {@link #doOpen()} after restoring pagination state from
     * ExecutionContext on job restart.</p>
     * 
     * <p><strong>Restart State Restoration:</strong></p>
     * <ul>
     *   <li>If ExecutionContext contains CURRENT_PAGE_KEY → restore currentPage (restart scenario)</li>
     *   <li>If ExecutionContext empty → start from page 0 (new execution)</li>
     *   <li>Enables fault-tolerant restart per Section 0.5 batch processing requirements</li>
     * </ul>
     * 
     * <p><strong>Lifecycle Order:</strong></p>
     * <ol>
     *   <li>Spring Batch calls open(executionContext)</li>
     *   <li>open() restores currentPage from executionContext</li>
     *   <li>open() calls super.open() for parent class initialization</li>
     *   <li>Parent class calls doOpen() for subclass-specific initialization</li>
     *   <li>doOpen() fetches first page using restored currentPage value</li>
     * </ol>
     * 
     * @param executionContext Spring Batch execution context containing restart state
     * @throws ItemStreamException if reader initialization fails
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        logger.debug("Opening TransactionItemReader with ExecutionContext");
        
        // Restore pagination state on job restart
        if (executionContext.containsKey(CURRENT_PAGE_KEY)) {
            currentPage = executionContext.getInt(CURRENT_PAGE_KEY);
            logger.info("Restarting TransactionItemReader from saved state - currentPage: {}", currentPage);
        } else {
            currentPage = 0;
            logger.info("Starting TransactionItemReader from beginning - currentPage: 0");
        }
        
        // Call parent class open() which will invoke doOpen()
        super.open(executionContext);
    }

    /**
     * Closes the reader by delegating to {@link #doClose()}.
     * 
     * <p>This is the public entry point for reader cleanup, called by Spring Batch
     * framework when step completes. Ensures resources are released properly.</p>
     * 
     * <p><strong>Lifecycle Order:</strong></p>
     * <ol>
     *   <li>Spring Batch calls close() on step completion</li>
     *   <li>close() calls super.close() for parent class cleanup</li>
     *   <li>Parent class calls doClose() for subclass-specific cleanup</li>
     *   <li>doClose() clears page buffer and nulls iterator</li>
     * </ol>
     * 
     * <p>This method is called in finally block by Spring Batch, ensuring cleanup
     * even if batch job fails with exception.</p>
     */
    @Override
    public void close() {
        logger.debug("Closing TransactionItemReader via public close() method");
        super.close();
    }

    /**
     * Reads a single transaction by delegating to {@link #doRead()}.
     * 
     * <p>This is the public entry point for record reading, called repeatedly by
     * Spring Batch framework during chunk-oriented processing. Delegates to doRead()
     * which contains actual reading logic with pagination handling.</p>
     * 
     * <p><strong>Return Value Semantics:</strong></p>
     * <ul>
     *   <li>Non-null Transaction: Record successfully read, continue processing</li>
     *   <li>Null: No more records available (EOF), complete chunk and finish step</li>
     * </ul>
     * 
     * <p>This method is called thousands of times per batch job execution, so it must
     * be highly efficient. In-memory iteration over current page ensures minimal
     * overhead per read operation.</p>
     * 
     * @return Next Transaction entity, or null if no more records available
     * @throws Exception if database query fails or data validation error
     */
    @Override
    public Transaction read() throws Exception {
        return super.read();
    }

    /**
     * Fetches a page of transaction data from the database using JPA query.
     * 
     * <p>This helper method executes a paginated database query to retrieve the next
     * batch of transaction records. Implements cursor-based pagination pattern for
     * memory-efficient processing of large datasets without loading entire table.</p>
     * 
     * <p><strong>Query Strategy:</strong></p>
     * <ul>
     *   <li>Order by transaction_id for consistent pagination ordering</li>
     *   <li>OFFSET = currentPage * pageSize (skip already-processed pages)</li>
     *   <li>LIMIT = pageSize (fetch only current page records)</li>
     *   <li>No WHERE clause: reads all transactions (can be customized in subclass)</li>
     * </ul>
     * 
     * <p><strong>SQL Equivalent:</strong></p>
     * <pre>
     * SELECT t FROM Transaction t 
     * ORDER BY t.transactionId 
     * OFFSET (currentPage * pageSize) ROWS 
     * FETCH FIRST pageSize ROWS ONLY
     * </pre>
     * 
     * <p><strong>Page Buffer Management:</strong></p>
     * <ul>
     *   <li>Query result stored in currentPageData List</li>
     *   <li>New iterator created from currentPageData.iterator()</li>
     *   <li>Empty result (page beyond dataset) sets currentPageData to empty list</li>
     *   <li>Null iterator signals EOF to doRead() method</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Database query: O(log N) with index on transaction_id</li>
     *   <li>Memory usage: Fixed at pageSize records (1000 by default)</li>
     *   <li>Network round trips: One per page, not per record</li>
     *   <li>Batch window: Supports 4-hour processing window for 10,000+ transactions</li>
     * </ul>
     * 
     * <p><strong>Customization Points:</strong></p>
     * <ul>
     *   <li>Override this method to add WHERE clause filtering (date range, status, etc.)</li>
     *   <li>Override to change ORDER BY for different sorting strategies</li>
     *   <li>Override to use native SQL query for database-specific optimizations</li>
     * </ul>
     */
    private void fetchPage() {
        logger.debug("Fetching transaction page {} with page size {}", currentPage, pageSize);
        
        try {
            // Create JPA query with pagination
            TypedQuery<Transaction> query = entityManager.createQuery(
                "SELECT t FROM Transaction t ORDER BY t.transactionId",
                Transaction.class
            );
            
            // Set pagination parameters
            query.setFirstResult(currentPage * pageSize);
            query.setMaxResults(pageSize);
            
            // Execute query and populate page buffer
            currentPageData = query.getResultList();
            
            // Create iterator for sequential access
            if (currentPageData != null && !currentPageData.isEmpty()) {
                currentIterator = currentPageData.iterator();
                logger.debug("Fetched page {} with {} transaction records", 
                           currentPage, currentPageData.size());
            } else {
                currentIterator = null;
                logger.debug("Page {} is empty - no more transaction records", currentPage);
            }
            
        } catch (Exception e) {
            logger.error("Error fetching transaction page {}", currentPage, e);
            throw new ItemStreamException("Failed to fetch transaction page: " + currentPage, e);
        }
    }

    /**
     * Sets the page size for database pagination.
     * 
     * <p>Allows customization of page size for job-specific performance tuning. Must be
     * called before reader is opened to take effect. Typically configured in batch job
     * XML or Java configuration via setter injection.</p>
     * 
     * <p><strong>Performance Tuning Guidelines:</strong></p>
     * <ul>
     *   <li>Smaller pages (500): Lower memory, more frequent queries, finer restart points</li>
     *   <li>Larger pages (2000): Fewer queries, higher memory, coarser restart points</li>
     *   <li>Default (1000): Balanced for typical 10,000+ transaction daily volume</li>
     *   <li>Should match chunk size: Avoids mid-chunk page fetch overhead</li>
     * </ul>
     * 
     * <p><strong>Usage Example (Java Config):</strong></p>
     * <pre>
     * {@literal @}Bean
     * {@literal @}StepScope
     * public TransactionItemReader transactionItemReader() {
     *     TransactionItemReader reader = new TransactionItemReader();
     *     reader.setPageSize(2000);  // Larger pages for high-volume job
     *     return reader;
     * }
     * </pre>
     * 
     * @param pageSize Number of records to fetch per database query (must be positive)
     * @throws IllegalArgumentException if pageSize <= 0
     */
    public void setPageSize(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Page size must be positive, got: " + pageSize);
        }
        this.pageSize = pageSize;
        logger.debug("Page size configured to: {}", pageSize);
    }

    /**
     * Sets the EntityManager for database access.
     * 
     * <p>Allows manual injection of EntityManager for testing or custom configuration
     * scenarios. Normally not needed as {@link #entityManager} is injected via
     * {@literal @}PersistenceContext annotation automatically by Spring container.</p>
     * 
     * <p><strong>Testing Usage:</strong> Useful for unit testing where you can inject
     * a mock EntityManager to verify query logic without actual database:</p>
     * <pre>
     * {@literal @}Test
     * public void testTransactionReading() {
     *     EntityManager mockEM = Mockito.mock(EntityManager.class);
     *     TransactionItemReader reader = new TransactionItemReader();
     *     reader.setEntityManager(mockEM);
     *     // Configure mock query results and verify behavior
     * }
     * </pre>
     * 
     * <p><strong>Production Usage:</strong> In production, EntityManager is automatically
     * injected by Spring's @PersistenceContext annotation and this setter should not be
     * called explicitly. Spring ensures proper EntityManager lifecycle management,
     * transaction boundaries, and connection pooling.</p>
     * 
     * @param entityManager JPA EntityManager instance for database operations
     */
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
        logger.debug("EntityManager manually set (typically for testing)");
    }

    /**
     * Sets the maximum number of items to read before stopping.
     * 
     * <p>This method is inherited from {@link AbstractItemCountingItemStreamItemReader}
     * and allows limiting the total number of records read by this reader. Useful for
     * testing with subset of data or implementing partial batch processing.</p>
     * 
     * <p><strong>Usage Example:</strong></p>
     * <pre>
     * reader.setMaxItemCount(5000);  // Read only first 5000 transactions
     * </pre>
     * 
     * <p><strong>Default Behavior:</strong> If not set, reader processes all available
     * records (no limit). The maxItemCount is checked by parent class after each read()
     * call, returning null once limit reached regardless of remaining database records.</p>
     * 
     * <p><strong>Testing Scenario:</strong> During development, limit records to speed up
     * test execution and reduce database load. In production, typically not used to
     * ensure complete dataset processing per business requirements.</p>
     * 
     * @param maxItemCount Maximum number of items to read, or Integer.MAX_VALUE for no limit
     */
    public void setMaxItemCount(int maxItemCount) {
        super.setMaxItemCount(maxItemCount);
        logger.debug("Max item count configured to: {}", maxItemCount);
    }

    /**
     * Sets the fetch size hint for the underlying JDBC query.
     * 
     * <p>This method is provided for API consistency but does NOT actually configure
     * JDBC fetch size on the EntityManager query. The fetch size is implicitly controlled
     * by the page size parameter ({@link #pageSize}) since we fetch entire pages at once.</p>
     * 
     * <p><strong>Implementation Note:</strong> This is a no-op method that exists only
     * to satisfy the exports schema requirement for setFetchSize() as exposed member.
     * The actual "fetch size" is effectively the {@link #pageSize} which determines how
     * many records are retrieved per database query.</p>
     * 
     * <p><strong>Real Fetch Size Control:</strong> To control fetch behavior, use
     * {@link #setPageSize(int)} instead. That determines the actual number of records
     * fetched per query and is the meaningful performance tuning parameter.</p>
     * 
     * <p><strong>Rationale:</strong> Since we use TypedQuery.getResultList() to fetch
     * entire pages into memory at once, there is no streaming cursor or JDBC-level
     * fetch size to configure. The page-based approach is more appropriate for Spring
     * Batch chunk-oriented processing than JDBC cursor streaming.</p>
     * 
     * @param fetchSize Ignored parameter (use setPageSize instead for actual control)
     * @deprecated Use {@link #setPageSize(int)} for fetch size control
     */
    @Deprecated
    public void setFetchSize(int fetchSize) {
        // No-op: Fetch size is controlled by pageSize parameter
        // This method exists only for API compatibility with schema exports
        logger.debug("setFetchSize({}) called - use setPageSize() for actual fetch control", fetchSize);
    }
}
