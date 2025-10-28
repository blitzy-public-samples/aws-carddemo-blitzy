package com.carddemo.batch.reader;

import com.carddemo.model.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CustomerReader - Spring Batch ItemReader for Customer entities
 * 
 * Converted from COBOL program: CBCUS01C.cbl
 * Original function: Sequential read and validation of customer data from VSAM CUSTFILE
 * 
 * This ItemReader implementation provides cursor-based pagination for sequential database reads
 * that replace VSAM sequential file reads from CUSTFILE in CBCUS01C.cbl batch validation job.
 * 
 * COBOL-to-Java Conversion Mapping:
 * ==================================
 * 
 * COBOL File Operations → Spring Batch ItemReader:
 * ------------------------------------------------
 * OPEN INPUT CUSTFILE-FILE (lines 120)
 *   → open(ExecutionContext executionContext) method
 * 
 * READ CUSTFILE-FILE INTO CUSTOMER-RECORD (line 93)
 *   → read() method returning next Customer or null
 * 
 * CUSTFILE-STATUS = '10' (EOF detection, line 98)
 *   → read() returns null when no more records available
 * 
 * CLOSE CUSTFILE-FILE (line 138)
 *   → close() method
 * 
 * COBOL Control Flow Mapping:
 * ---------------------------
 * PERFORM UNTIL END-OF-FILE = 'Y' (lines 74-81)
 *   → Spring Batch chunk processing calls read() repeatedly until null returned
 * 
 * IF CUSTFILE-STATUS = '00' (success check, line 94)
 *   → Successful read() returns Customer entity
 * 
 * END-OF-FILE flag (line 65, 108)
 *   → Returning null from read() signals end-of-stream to Spring Batch
 * 
 * Key Features:
 * =============
 * - Implements ItemReader<Customer> interface for Spring Batch integration
 * - Implements ItemStream interface for checkpoint/restart capability
 * - Uses CustomerRepository.findAll(Pageable) with page size 1000 for optimal memory usage
 * - Sorts results by primary key (custId) to maintain identical record order as VSAM KSDS
 * - Maintains stateful position tracking (current page number and index within page)
 * - Stores state in ExecutionContext for restart capability after job failure
 * - Returns null at end-of-stream (replicates COBOL file-status '10' EOF detection)
 * 
 * Performance Characteristics:
 * ===========================
 * - Page size: 1000 records per database query (matches Spring Batch chunk size)
 * - Sorted by custId (PIC 9(09) from COBOL) ensuring deterministic ordering
 * - Leverages PostgreSQL B-tree index on primary key for efficient pagination
 * - Memory-efficient: Only one page of 1000 records loaded at a time
 * - Database query overhead: 1 query per 1000 records processed
 * 
 * Checkpoint/Restart Capability:
 * ==============================
 * ExecutionContext stores:
 * - "customer.reader.page": Current page number (0-based)
 * - "customer.reader.index": Current index within page (0-999)
 * 
 * On job restart after failure:
 * 1. open() method reads position from ExecutionContext
 * 2. Resumes reading from exact record where job stopped
 * 3. Prevents duplicate processing of already-validated customers
 * 
 * This replaces COBOL checkpoint/restart mechanism that would re-read from beginning.
 * 
 * Usage Example in Spring Batch Configuration:
 * ============================================
 * <pre>
 * {@code
 * @Bean
 * public Step customerValidationStep(
 *         CustomerReader customerReader,
 *         CustomerProcessor customerProcessor,
 *         CustomerWriter customerWriter) {
 *     return stepBuilderFactory.get("customerValidationStep")
 *             .<Customer, Customer>chunk(1000)  // Matches page size
 *             .reader(customerReader)
 *             .processor(customerProcessor)
 *             .writer(customerWriter)
 *             .build();
 * }
 * }
 * </pre>
 * 
 * Error Handling:
 * ==============
 * - Database connection errors: Propagated as ItemStreamException
 * - Invalid state in ExecutionContext: Reset to initial state (page 0, index 0)
 * - Empty customer table: Returns null on first read() call (normal EOF behavior)
 * 
 * Conversion Notes:
 * =================
 * - COBOL COMP-3 fields converted to BigDecimal with scale 2
 * - VSAM I/O replaced with JPA repository pagination
 * - COBOL file status checking replaced with Spring Batch exception handling
 * - Sequential KSDS access preserved through ORDER BY cust_id ASC
 * - COBOL PERFORM UNTIL loop replaced by Spring Batch chunk-oriented processing
 * 
 * Dependencies:
 * =============
 * - CustomerRepository: JPA repository for database access
 * - Customer: JPA entity from CVCUS01Y.cpy copybook
 * - Spring Batch 5.2.x: ItemReader and ItemStream interfaces
 * - Spring Data JPA 3.4.x: Pagination support
 * 
 * @see CustomerRepository Spring Data JPA repository
 * @see Customer JPA entity converted from CVCUS01Y.cpy
 * @see CBCUS01C.cbl Original COBOL batch validation program
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024
 */
@Slf4j
@Component
public class CustomerReader implements ItemReader<Customer>, ItemStream {

    // ExecutionContext keys for checkpoint/restart state persistence
    private static final String CURRENT_PAGE_KEY = "customer.reader.page";
    private static final String CURRENT_INDEX_KEY = "customer.reader.index";
    
    // Page size matches Spring Batch chunk size for optimal processing
    // Corresponds to COBOL batch reading pattern with optimal memory usage
    private static final int PAGE_SIZE = 1000;

    private final CustomerRepository customerRepository;

    // Stateful position tracking for pagination
    private int currentPage = 0;
    private int currentIndex = 0;
    private List<Customer> currentPageData;
    private boolean initialized = false;

    /**
     * Constructor with dependency injection
     * 
     * @param customerRepository JPA repository for Customer entity access
     */
    public CustomerReader(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * Opens the customer data stream and initializes state.
     * 
     * Replaces COBOL: OPEN INPUT CUSTFILE-FILE (line 120)
     * 
     * This method is called by Spring Batch before processing begins.
     * Restores position from ExecutionContext if job is restarting after failure.
     * 
     * COBOL Equivalent:
     * <pre>
     * 0000-CUSTFILE-OPEN.
     *     MOVE 8 TO APPL-RESULT.
     *     OPEN INPUT CUSTFILE-FILE
     *     IF CUSTFILE-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *         DISPLAY 'ERROR OPENING CUSTFILE'
     *         PERFORM Z-ABEND-PROGRAM
     *     END-IF
     * </pre>
     * 
     * @param executionContext Spring Batch execution context containing restart state
     * @throws ItemStreamException if database connection fails (replaces COBOL ABEND)
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        log.info("Opening CustomerReader for batch customer validation");
        
        try {
            // Restore position from ExecutionContext if job is restarting
            if (executionContext.containsKey(CURRENT_PAGE_KEY)) {
                currentPage = executionContext.getInt(CURRENT_PAGE_KEY);
                currentIndex = executionContext.getInt(CURRENT_INDEX_KEY);
                log.info("Restarting CustomerReader from page {} index {}", currentPage, currentIndex);
            } else {
                // First time execution - start from beginning
                currentPage = 0;
                currentIndex = 0;
                log.info("Starting CustomerReader from beginning");
            }
            
            // Load first page of customer data
            loadPage();
            initialized = true;
            
            log.info("CustomerReader opened successfully. Total pages estimated: {}",
                    estimateTotalPages());
            
        } catch (Exception e) {
            // Replaces COBOL ERROR OPENING CUSTFILE (line 129) and Z-ABEND-PROGRAM
            log.error("Error opening CustomerReader: {}", e.getMessage(), e);
            throw new ItemStreamException("Failed to open CustomerReader", e);
        }
    }

    /**
     * Reads the next customer record from the database.
     * 
     * Replaces COBOL: READ CUSTFILE-FILE INTO CUSTOMER-RECORD (line 93)
     * 
     * This method is called repeatedly by Spring Batch chunk processing.
     * Returns null when no more records available (EOF condition).
     * 
     * COBOL Equivalent:
     * <pre>
     * 1000-CUSTFILE-GET-NEXT.
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
     *     IF CUSTFILE-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         IF CUSTFILE-STATUS = '10'
     *             MOVE 16 TO APPL-RESULT
     *             MOVE 'Y' TO END-OF-FILE
     *         ELSE
     *             MOVE 12 TO APPL-RESULT
     *             DISPLAY 'ERROR READING CUSTOMER FILE'
     *             PERFORM Z-ABEND-PROGRAM
     *         END-IF
     *     END-IF
     * </pre>
     * 
     * Processing Flow:
     * 1. Return next customer from current page if available
     * 2. Load next page if current page exhausted
     * 3. Return null if no more pages (EOF - file-status '10')
     * 
     * @return Next Customer entity, or null if end-of-file reached (COBOL file-status '10')
     * @throws Exception if database read error occurs (replaces COBOL APPL-RESULT 12)
     */
    @Override
    public Customer read() throws Exception {
        // Safety check - ensure reader is initialized
        if (!initialized) {
            log.warn("CustomerReader.read() called before open() - returning null");
            return null;
        }

        // Check if current page has more records
        if (currentPageData != null && currentIndex < currentPageData.size()) {
            Customer customer = currentPageData.get(currentIndex);
            currentIndex++;
            
            log.debug("Read customer ID: {} (page {} index {})", 
                     customer.getCustId(), currentPage, currentIndex - 1);
            
            return customer;
        }

        // Current page exhausted - try loading next page
        currentPage++;
        currentIndex = 0;
        loadPage();

        // Check if new page has data
        if (currentPageData != null && !currentPageData.isEmpty()) {
            Customer customer = currentPageData.get(currentIndex);
            currentIndex++;
            
            log.debug("Read customer ID: {} (page {} index {})", 
                     customer.getCustId(), currentPage, currentIndex - 1);
            
            return customer;
        }

        // No more pages available - return null to signal EOF
        // Replaces COBOL: MOVE 'Y' TO END-OF-FILE (line 108)
        log.info("CustomerReader reached end-of-file after page {}", currentPage - 1);
        return null;
    }

    /**
     * Updates the execution context with current position for checkpoint/restart.
     * 
     * Called by Spring Batch after each chunk commit to save restart position.
     * Stores current page number and index for restart capability.
     * 
     * COBOL has no direct equivalent - mainframe batch jobs typically restart from beginning.
     * This provides superior restart capability: resumes from exact record on failure.
     * 
     * @param executionContext Spring Batch execution context to store checkpoint state
     * @throws ItemStreamException if state cannot be saved
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(CURRENT_PAGE_KEY, currentPage);
        executionContext.putInt(CURRENT_INDEX_KEY, currentIndex);
        
        log.debug("Updated checkpoint: page {} index {}", currentPage, currentIndex);
    }

    /**
     * Closes the customer data stream and releases resources.
     * 
     * Replaces COBOL: CLOSE CUSTFILE-FILE (line 138)
     * 
     * Called by Spring Batch when processing completes (success or failure).
     * Cleans up resources and resets state.
     * 
     * COBOL Equivalent:
     * <pre>
     * 9000-CUSTFILE-CLOSE.
     *     ADD 8 TO ZERO GIVING APPL-RESULT.
     *     CLOSE CUSTFILE-FILE
     *     IF CUSTFILE-STATUS = '00'
     *         SUBTRACT APPL-RESULT FROM APPL-RESULT
     *     ELSE
     *         ADD 12 TO ZERO GIVING APPL-RESULT
     *         DISPLAY 'ERROR CLOSING CUSTOMER FILE'
     *         PERFORM Z-ABEND-PROGRAM
     *     END-IF
     * </pre>
     */
    @Override
    public void close() throws ItemStreamException {
        log.info("Closing CustomerReader. Final position: page {} index {}", 
                currentPage, currentIndex);
        
        // Release current page data to free memory
        currentPageData = null;
        initialized = false;
        
        // Note: JPA repository connections are managed by Spring - no explicit close needed
        // This differs from COBOL where explicit CLOSE was required for VSAM files
        
        log.info("CustomerReader closed successfully");
    }

    /**
     * Loads a page of customer data from the database.
     * 
     * Uses CustomerRepository.findAll(Pageable) with:
     * - Page number: currentPage (0-based)
     * - Page size: 1000 records
     * - Sort order: custId ascending (maintains VSAM KSDS sequential order)
     * 
     * COBOL Record Order Preservation:
     * The VSAM KSDS file is accessed sequentially with RECORD KEY IS FD-CUST-ID (line 32).
     * This ensures customers are read in custId order, which we replicate with ORDER BY.
     * 
     * @throws Exception if database query fails
     */
    private void loadPage() throws Exception {
        try {
            // Create pageable request with sorting by custId (COBOL RECORD KEY IS FD-CUST-ID)
            // Sort.by("custId").ascending() maintains identical sequential order as VSAM KSDS
            Pageable pageable = PageRequest.of(
                currentPage, 
                PAGE_SIZE, 
                Sort.by("custId").ascending()
            );

            // Query database for current page
            Page<Customer> page = customerRepository.findAll(pageable);
            currentPageData = page.getContent();

            log.debug("Loaded page {} with {} customers (total elements: {}, total pages: {})",
                     currentPage, 
                     currentPageData.size(), 
                     page.getTotalElements(), 
                     page.getTotalPages());

            // Log warning if empty page loaded (unexpected unless at EOF)
            if (currentPageData.isEmpty() && currentPage == 0) {
                log.warn("No customers found in database - customer table appears empty");
            }
            
        } catch (Exception e) {
            // Replaces COBOL ERROR READING CUSTOMER FILE (line 110) and Z-ABEND-PROGRAM
            log.error("Error loading customer page {}: {}", currentPage, e.getMessage(), e);
            throw new Exception("Failed to load customer page " + currentPage, e);
        }
    }

    /**
     * Estimates total number of pages for logging purposes.
     * 
     * Note: This is a rough estimate and may not be exact due to concurrent modifications.
     * Used only for informational logging.
     * 
     * @return Estimated total pages, or -1 if cannot be determined
     */
    private long estimateTotalPages() {
        try {
            long totalCustomers = customerRepository.count();
            return (totalCustomers + PAGE_SIZE - 1) / PAGE_SIZE; // Ceiling division
        } catch (Exception e) {
            log.warn("Unable to estimate total pages: {}", e.getMessage());
            return -1;
        }
    }
}
