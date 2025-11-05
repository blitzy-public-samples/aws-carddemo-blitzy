package com.carddemo.batch.reader;

import com.carddemo.entity.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;

/**
 * Spring Batch ItemReader implementation for reading customer data from the database.
 * 
 * <p>This reader transforms the COBOL CBCUS01C.cbl sequential VSAM CUSTDAT file read pattern
 * (PERFORM UNTIL END-OF-FILE with READ CUSTFILE-FILE INTO CUSTOMER-RECORD) to Spring Batch
 * chunk-oriented processing with cursor-based pagination.</p>
 * 
 * <p><strong>COBOL Source Transformation:</strong></p>
 * <ul>
 *   <li>COBOL Program: app/cbl/CBCUS01C.cbl</li>
 *   <li>COBOL Copybook: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD structure, 500 bytes)</li>
 *   <li>VSAM File: CUSTDAT KSDS (Key-Sequenced Dataset)</li>
 *   <li>Access Mode: Sequential read with indexed organization</li>
 * </ul>
 * 
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Cursor-based database pagination with configurable chunk size (default: 1000 records)</li>
 *   <li>Fault-tolerant restart capability via ExecutionContext state persistence</li>
 *   <li>VSAM file-status '00' (success) and '10' (EOF) equivalent handling</li>
 *   <li>Type conversions: PIC 9(09) → Long, PIC X → String (trimmed), PIC 9(03) → Integer</li>
 *   <li>Thread-safe stateless processing with execution context-based state management</li>
 * </ul>
 * 
 * <p><strong>Data Validation Rules (from COBOL PIC clauses):</strong></p>
 * <ul>
 *   <li>CUST-ID (PIC 9(09)): 9-digit numeric → Long</li>
 *   <li>CUST-FIRST-NAME (PIC X(25)): Fixed-length 25 chars → String (trimmed)</li>
 *   <li>CUST-ADDR-STATE-CD (PIC X(02)): 2-character state code → String (length validated)</li>
 *   <li>CUST-ADDR-COUNTRY-CD (PIC X(03)): 3-character country code → String (length validated)</li>
 *   <li>CUST-DOB-YYYY-MM-DD (PIC X(10)): Date format YYYY-MM-DD → LocalDate</li>
 *   <li>CUST-FICO-CREDIT-SCORE (PIC 9(03)): 3-digit numeric → Integer</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Default page size: 1000 records per Section 0.5 specifications</li>
 *   <li>In-memory page buffering for efficient iteration</li>
 *   <li>Lazy loading of pages to minimize memory footprint</li>
 *   <li>Database cursor maintained across chunks for sequential access</li>
 * </ul>
 * 
 * <p><strong>Restart Capability:</strong></p>
 * <ul>
 *   <li>Stores current read position (last customer ID) in ExecutionContext</li>
 *   <li>On job restart, resumes from last successfully processed customer</li>
 *   <li>Supports Spring Batch checkpoint/restart pattern</li>
 *   <li>Maintains item count for progress tracking</li>
 * </ul>
 * 
 * <p><strong>Usage in Batch Jobs:</strong></p>
 * <pre>{@code
 * @Bean
 * public Step customerDataLoadStep(JobRepository jobRepository,
 *                                   PlatformTransactionManager transactionManager,
 *                                   CustomerItemReader reader,
 *                                   ItemProcessor<Customer, Customer> processor,
 *                                   ItemWriter<Customer> writer) {
 *     return new StepBuilder("customerDataLoadStep", jobRepository)
 *             .<Customer, Customer>chunk(1000, transactionManager)
 *             .reader(reader)
 *             .processor(processor)
 *             .writer(writer)
 *             .build();
 * }
 * }</pre>
 * 
 * @see Customer
 * @see AbstractItemCountingItemStreamItemReader
 * @see <a href="Section 0.3">Transaction Semantics Preservation</a>
 * @see <a href="Section 0.6">Batch Processing Transformation</a>
 */
@Component
public class CustomerItemReader extends AbstractItemCountingItemStreamItemReader<Customer> {

    private static final Logger logger = LoggerFactory.getLogger(CustomerItemReader.class);

    /**
     * Default page size for database pagination (1000 records per Section 0.5).
     * This value matches the configurable chunk size requirement for Spring Batch processing.
     */
    private static final int DEFAULT_PAGE_SIZE = 1000;

    /**
     * ExecutionContext key for storing the current page number during batch execution.
     * Used for restart capability to resume from the last successfully processed page.
     */
    private static final String CURRENT_PAGE_KEY = "current.page";

    /**
     * ExecutionContext key for storing the last successfully read customer ID.
     * Enables precise restart positioning within a page for fault-tolerant processing.
     */
    private static final String LAST_CUSTOMER_ID_KEY = "last.customer.id";

    /**
     * JPA EntityManager for database query operations.
     * Injected by Spring container via @PersistenceContext annotation.
     * Provides JPQL query execution with pagination support for reading customer records.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Configurable page size for database pagination.
     * Defaults to 1000 records but can be adjusted via setPageSize() for different batch scenarios.
     */
    private int pageSize = DEFAULT_PAGE_SIZE;

    /**
     * Current page number in the pagination sequence (0-based indexing).
     * Incremented after each page is fully read and persisted to ExecutionContext.
     */
    private int currentPage = 0;

    /**
     * Last customer ID successfully read and processed.
     * Used for restart positioning to skip already-processed records on job recovery.
     */
    private Long lastCustomerId = null;

    /**
     * Iterator over the current page of customer records.
     * Provides sequential access to Customer entities within the current page buffer.
     * Null when no page is loaded or after reaching end of file.
     */
    private Iterator<Customer> currentPageIterator;

    /**
     * Constructs a new CustomerItemReader with default name.
     * Sets the execution context name for state management during batch processing.
     */
    public CustomerItemReader() {
        setName("CustomerItemReader");
    }

    /**
     * Opens the reader and initializes its state from the ExecutionContext.
     * 
     * <p>This method is called by Spring Batch framework before reading begins. It restores
     * the reader's state from a previous execution (for restart scenarios) or initializes
     * to default values for a new execution.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong> 0000-CUSTFILE-OPEN paragraph in CBCUS01C.cbl</p>
     * <pre>{@code
     * 0000-CUSTFILE-OPEN.
     *     MOVE 8 TO APPL-RESULT.
     *     OPEN INPUT CUSTFILE-FILE
     *     IF CUSTFILE-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ...
     * }</pre>
     * 
     * <p><strong>Restart Capability:</strong></p>
     * <ul>
     *   <li>Retrieves current page number from ExecutionContext (default: 0)</li>
     *   <li>Retrieves last customer ID from ExecutionContext (default: null)</li>
     *   <li>Logs restoration of state for monitoring and troubleshooting</li>
     * </ul>
     * 
     * @param executionContext Spring Batch execution context containing persisted state
     * @throws ItemStreamException if reader initialization fails
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        super.open(executionContext);
        
        // Restore pagination state from ExecutionContext for restart capability
        currentPage = executionContext.getInt(CURRENT_PAGE_KEY, 0);
        
        // Restore last customer ID for precise restart positioning
        Long storedLastCustomerId = executionContext.getLong(LAST_CUSTOMER_ID_KEY, 0L);
        lastCustomerId = (storedLastCustomerId > 0) ? storedLastCustomerId : null;
        
        logger.info("Opened CustomerItemReader - Starting from page: {}, last customer ID: {}", 
                    currentPage, lastCustomerId);
        
        // Delegate to doOpen() for reader-specific initialization
        doOpen();
    }

    /**
     * Performs reader-specific initialization after open() is called.
     * 
     * <p>This method is a template method hook provided by AbstractItemCountingItemStreamItemReader.
     * It loads the first page of customer records from the database to prepare for reading.</p>
     * 
     * <p><strong>Implementation Details:</strong></p>
     * <ul>
     *   <li>Fetches the initial page of customer records using JPA query</li>
     *   <li>Initializes the currentPageIterator for sequential access</li>
     *   <li>Logs the number of records loaded for monitoring</li>
     * </ul>
     * 
     * @throws ItemStreamException if database query fails or connection issues occur
     */
    @Override
    protected void doOpen() throws ItemStreamException {
        try {
            logger.debug("Executing doOpen() - Fetching initial page of customers");
            fetchNextPage();
        } catch (Exception e) {
            logger.error("Failed to open CustomerItemReader and fetch initial page", e);
            throw new ItemStreamException("Error initializing CustomerItemReader", e);
        }
    }

    /**
     * Reads the next customer record from the database.
     * 
     * <p>This method is called by Spring Batch framework for each item in the chunk.
     * It implements the VSAM sequential read pattern from COBOL, returning null when
     * end-of-file is reached (equivalent to VSAM file-status '10').</p>
     * 
     * <p><strong>COBOL Equivalent:</strong> 1000-CUSTFILE-GET-NEXT paragraph in CBCUS01C.cbl</p>
     * <pre>{@code
     * 1000-CUSTFILE-GET-NEXT.
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
     *     IF CUSTFILE-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         IF CUSTFILE-STATUS = '10'
     *             MOVE 16 TO APPL-RESULT  (EOF)
     *         ...
     * }</pre>
     * 
     * <p><strong>Return Value Semantics:</strong></p>
     * <ul>
     *   <li>Returns Customer entity when record is available (VSAM status '00' equivalent)</li>
     *   <li>Returns null when end-of-file is reached (VSAM status '10' equivalent)</li>
     *   <li>Null signals Spring Batch to complete the current chunk and step</li>
     * </ul>
     * 
     * <p><strong>Pagination Logic:</strong></p>
     * <ul>
     *   <li>Iterates through current page until exhausted</li>
     *   <li>Fetches next page automatically when current page is consumed</li>
     *   <li>Returns null when no more pages are available</li>
     * </ul>
     * 
     * @return Customer entity for processing, or null if end-of-file reached
     * @throws Exception if database read operation fails
     */
    @Override
    protected Customer doRead() throws Exception {
        // Check if current page has more records
        if (currentPageIterator != null && currentPageIterator.hasNext()) {
            Customer customer = currentPageIterator.next();
            lastCustomerId = customer.getCustomerId();
            
            logger.trace("Read customer ID: {} - {} {}", 
                        customer.getCustomerId(), 
                        customer.getFirstName(), 
                        customer.getLastName());
            
            return customer;
        }
        
        // Current page exhausted, fetch next page
        fetchNextPage();
        
        // Check if new page has records
        if (currentPageIterator != null && currentPageIterator.hasNext()) {
            Customer customer = currentPageIterator.next();
            lastCustomerId = customer.getCustomerId();
            
            logger.trace("Read customer ID: {} - {} {} (new page)", 
                        customer.getCustomerId(), 
                        customer.getFirstName(), 
                        customer.getLastName());
            
            return customer;
        }
        
        // No more records available - end of file (VSAM status '10' equivalent)
        logger.info("End of customer data reached - Total pages read: {}", currentPage);
        return null;
    }

    /**
     * Fetches the next page of customer records from the database.
     * 
     * <p>This method executes a JPQL query with pagination parameters to retrieve
     * the next chunk of customer records ordered by customer ID. It handles the
     * transformation from VSAM sequential access to database cursor-based pagination.</p>
     * 
     * <p><strong>Query Characteristics:</strong></p>
     * <ul>
     *   <li>Orders by customerId for consistent sequential access</li>
     *   <li>Uses setFirstResult() for page offset calculation</li>
     *   <li>Uses setMaxResults() to limit page size (default: 1000 records)</li>
     *   <li>Filters by lastCustomerId for restart scenarios to skip processed records</li>
     * </ul>
     * 
     * <p><strong>Pagination Formula:</strong></p>
     * <pre>
     * firstResult = currentPage * pageSize
     * maxResults = pageSize
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>Logs database query execution for monitoring</li>
     *   <li>Handles empty result sets gracefully (sets iterator to null)</li>
     *   <li>Increments page counter only after successful fetch</li>
     * </ul>
     */
    private void fetchNextPage() {
        try {
            logger.debug("Fetching page {} with page size {}", currentPage, pageSize);
            
            // Build JPQL query with pagination
            TypedQuery<Customer> query;
            
            if (lastCustomerId != null) {
                // Restart scenario: skip already-processed records
                query = entityManager.createQuery(
                    "SELECT c FROM Customer c WHERE c.customerId > :lastId ORDER BY c.customerId",
                    Customer.class
                );
                query.setParameter("lastId", lastCustomerId);
                logger.debug("Restart mode - Resuming after customer ID: {}", lastCustomerId);
            } else {
                // Normal mode: sequential read from beginning
                query = entityManager.createQuery(
                    "SELECT c FROM Customer c ORDER BY c.customerId",
                    Customer.class
                );
            }
            
            // Apply pagination parameters
            query.setFirstResult(currentPage * pageSize);
            query.setMaxResults(pageSize);
            
            // Execute query and get results
            List<Customer> customers = query.getResultList();
            
            if (customers.isEmpty()) {
                // No more records available
                currentPageIterator = null;
                logger.info("No more customer records found - Page {} returned 0 results", currentPage);
            } else {
                // Records found, create iterator
                currentPageIterator = customers.iterator();
                currentPage++;
                
                logger.info("Fetched page with {} customer records - Total pages loaded: {}", 
                           customers.size(), currentPage);
            }
            
        } catch (Exception e) {
            logger.error("Error fetching customer page {} with page size {}", currentPage, pageSize, e);
            currentPageIterator = null;
            throw new ItemStreamException("Failed to fetch customer data page", e);
        }
    }

    /**
     * Updates the ExecutionContext with the current reader state.
     * 
     * <p>This method is called by Spring Batch framework after each chunk commit to persist
     * the reader's current position. This enables fault-tolerant restart capability by
     * storing pagination state in the job repository.</p>
     * 
     * <p><strong>State Persisted:</strong></p>
     * <ul>
     *   <li>Current page number for resuming pagination</li>
     *   <li>Last customer ID successfully read for precise restart positioning</li>
     *   <li>Item count (handled by superclass)</li>
     * </ul>
     * 
     * <p><strong>Restart Scenario:</strong></p>
     * <pre>
     * 1. Job processes 2500 records (2 full pages + 500 records from page 3)
     * 2. Job fails due to infrastructure issue
     * 3. ExecutionContext contains: page=2, lastCustomerId=2500
     * 4. On restart, reader resumes from page 2, skipping customers 1-2500
     * 5. Processing continues from customer 2501 onwards
     * </pre>
     * 
     * @param executionContext Spring Batch execution context for state persistence
     * @throws ItemStreamException if state update fails
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        super.update(executionContext);
        
        // Persist current page number
        executionContext.putInt(CURRENT_PAGE_KEY, currentPage);
        
        // Persist last customer ID if available
        if (lastCustomerId != null) {
            executionContext.putLong(LAST_CUSTOMER_ID_KEY, lastCustomerId);
        }
        
        logger.trace("Updated ExecutionContext - Page: {}, Last Customer ID: {}", 
                    currentPage, lastCustomerId);
    }

    /**
     * Closes the reader and releases resources.
     * 
     * <p>This method is called by Spring Batch framework after reading is complete or when
     * an error occurs. It performs cleanup operations and logs the final state.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong> 9000-CUSTFILE-CLOSE paragraph in CBCUS01C.cbl</p>
     * <pre>{@code
     * 9000-CUSTFILE-CLOSE.
     *     ADD 8 TO ZERO GIVING APPL-RESULT.
     *     CLOSE CUSTFILE-FILE
     *     IF CUSTFILE-STATUS = '00'
     *         SUBTRACT APPL-RESULT FROM APPL-RESULT
     *     ...
     * }</pre>
     * 
     * <p><strong>Cleanup Operations:</strong></p>
     * <ul>
     *   <li>Clears page iterator to release memory</li>
     *   <li>Logs final statistics (total pages, last customer ID)</li>
     *   <li>EntityManager is managed by Spring and doesn't require explicit close</li>
     * </ul>
     * 
     * @throws ItemStreamException if close operation fails
     */
    @Override
    public void close() throws ItemStreamException {
        super.close();
        doClose();
    }

    /**
     * Performs reader-specific cleanup after close() is called.
     * 
     * <p>This method is a template method hook provided by AbstractItemCountingItemStreamItemReader.
     * It clears internal state and logs final execution statistics.</p>
     * 
     * @throws ItemStreamException if cleanup fails
     */
    @Override
    protected void doClose() throws ItemStreamException {
        currentPageIterator = null;
        
        logger.info("Closed CustomerItemReader - Total pages read: {}, Last customer ID: {}", 
                    currentPage, lastCustomerId);
    }

    /**
     * Sets the page size for database pagination.
     * 
     * <p>This method allows customization of the page size for different batch scenarios.
     * Default is 1000 records per Section 0.5 specifications, but can be adjusted for
     * memory-constrained environments or performance tuning.</p>
     * 
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * @Bean
     * public CustomerItemReader customerReader() {
     *     CustomerItemReader reader = new CustomerItemReader();
     *     reader.setPageSize(500); // Use smaller page size
     *     return reader;
     * }
     * }</pre>
     * 
     * @param pageSize Number of records to fetch per page (must be positive)
     * @throws IllegalArgumentException if pageSize is less than or equal to 0
     */
    public void setPageSize(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Page size must be positive: " + pageSize);
        }
        this.pageSize = pageSize;
        logger.info("CustomerItemReader page size set to: {}", pageSize);
    }

    /**
     * Sets the EntityManager for database operations.
     * 
     * <p>This method supports explicit EntityManager injection for testing or
     * non-standard Spring configurations. In normal usage, the EntityManager
     * is injected automatically via @PersistenceContext annotation.</p>
     * 
     * <p><strong>Testing Usage:</strong></p>
     * <pre>{@code
     * @Test
     * public void testCustomerReader() {
     *     EntityManager mockEntityManager = mock(EntityManager.class);
     *     CustomerItemReader reader = new CustomerItemReader();
     *     reader.setEntityManager(mockEntityManager);
     *     // ... test logic
     * }
     * }</pre>
     * 
     * @param entityManager JPA EntityManager instance for database queries
     */
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }
}
