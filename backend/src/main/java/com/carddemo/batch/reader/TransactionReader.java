package com.carddemo.batch.reader;

import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
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
 * Spring Batch ItemReader implementation for Transaction entities.
 * 
 * Converted from COBOL program: CBTRN01C.cbl (Daily transaction file validation)
 * Original function: Sequential read of DALYTRAN VSAM file for transaction processing
 * 
 * This reader replaces COBOL VSAM sequential file access pattern:
 * <pre>
 * COBOL (CBTRN01C.cbl lines 29-32):
 *   SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
 *          ORGANIZATION IS SEQUENTIAL
 *          ACCESS MODE  IS SEQUENTIAL
 *          FILE STATUS  IS DALYTRAN-STATUS.
 * 
 * COBOL (CBTRN01C.cbl lines 203-224):
 *   1000-DALYTRAN-GET-NEXT.
 *       READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
 *       IF  DALYTRAN-STATUS = '00'
 *           MOVE 0 TO APPL-RESULT
 *       ELSE
 *           IF  DALYTRAN-STATUS = '10'
 *               MOVE 16 TO APPL-RESULT
 *           ELSE
 *               MOVE 12 TO APPL-RESULT
 *           END-IF
 *       END-IF
 *       IF  APPL-AOK
 *           CONTINUE
 *       ELSE
 *           IF  APPL-EOF
 *               MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
 *           ELSE
 *               DISPLAY 'ERROR READING DAILY TRANSACTION FILE'
 *               PERFORM Z-ABEND-PROGRAM
 *           END-IF
 *       END-IF.
 * </pre>
 * 
 * Java Conversion Strategy:
 * - VSAM sequential READ → JPA repository findAll(Pageable) with pagination
 * - File status '00' (success) → Successful page load
 * - File status '10' (EOF) → Return null from read() method
 * - File status errors → ItemStreamException thrown
 * - END-OF-DAILY-TRANS-FILE flag → Null return indicating end-of-stream
 * 
 * Implements ItemReader<Transaction> interface providing read() method that returns
 * next Transaction entity or null when all records have been processed (EOF equivalent).
 * 
 * Implements ItemStream interface providing checkpoint/restart capability through
 * ExecutionContext state management:
 * - open(ExecutionContext) - Initialize reader state, load first page
 * - update(ExecutionContext) - Save current position for checkpoint/restart
 * - close() - Clean up reader resources
 * 
 * Key Features:
 * - Cursor-based pagination with page size 1000 records matching Spring Batch chunk size
 * - Stateful position tracking (current page number, index within page) in ExecutionContext
 * - Primary key sorting (trans_id) maintains identical record order as VSAM KSDS sequential access
 * - Memory-efficient processing - only current page held in memory at a time
 * - Checkpoint/restart support - job can resume from last successful position on failure
 * 
 * Performance Characteristics:
 * - Page size 1000 optimizes database round-trips vs memory usage
 * - B-tree index on trans_id ensures efficient ordered retrieval
 * - Stateless between chunks - ExecutionContext provides only state
 * - Suitable for processing millions of transaction records in daily batch jobs
 * 
 * Usage in Batch Jobs:
 * This reader is configured in TransactionProcessingJobConfig for daily transaction
 * processing jobs (CBTRN01C.cbl, CBTRN02C.cbl, CBTRN03C.cbl equivalents):
 * <pre>
 * @Bean
 * public Step transactionProcessingStep(
 *         JobRepository jobRepository,
 *         PlatformTransactionManager transactionManager,
 *         TransactionReader reader,
 *         TransactionProcessor processor,
 *         TransactionWriter writer) {
 *     return new StepBuilder("transactionProcessingStep", jobRepository)
 *             .<Transaction, Transaction>chunk(1000, transactionManager)
 *             .reader(reader)
 *             .processor(processor)
 *             .writer(writer)
 *             .build();
 * }
 * </pre>
 * 
 * Referenced by:
 * - TransactionProcessingJobConfig (batch job configuration)
 * - Spring Batch framework (invokes read() method repeatedly until null returned)
 * 
 * @see Transaction
 * @see TransactionRepository
 * @see com.carddemo.batch.processor.TransactionProcessor
 * @see com.carddemo.batch.writer.TransactionWriter
 * @see com.carddemo.batch.config.TransactionProcessingJobConfig
 */
@Component
@Slf4j
public class TransactionReader implements ItemReader<Transaction>, ItemStream {

    /**
     * ExecutionContext key for storing current page number.
     * Used for checkpoint/restart capability - if job fails and restarts,
     * reader resumes from last successfully processed page.
     */
    private static final String CURRENT_PAGE_KEY = "transaction.reader.current.page";

    /**
     * ExecutionContext key for storing current index within page.
     * Used for checkpoint/restart capability - if job fails and restarts,
     * reader resumes from last successfully processed record within page.
     */
    private static final String CURRENT_INDEX_KEY = "transaction.reader.current.index";

    /**
     * Page size for database queries.
     * Set to 1000 to match Spring Batch chunk size for optimal memory usage.
     * Each chunk processes one page, minimizing memory footprint while
     * maintaining efficient database access patterns.
     */
    private static final int PAGE_SIZE = 1000;

    /**
     * Spring Data JPA repository for Transaction entity database access.
     * Provides findAll(Pageable) method for paginated query execution.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Current page of Transaction entities loaded from database.
     * Holds page metadata (total elements, total pages, current page number)
     * and content (list of Transaction entities for current page).
     * Null before first page load.
     */
    private Page<Transaction> currentPage;

    /**
     * Current page number being processed (0-based).
     * Incremented after exhausting current page to load next page.
     * Reset to 0 in open() method for new job execution.
     * Restored from ExecutionContext in open() for restart scenario.
     */
    private int currentPageNumber;

    /**
     * Current index within page being processed (0-based).
     * Incremented after each successful read() call.
     * Reset to 0 when moving to next page.
     * Restored from ExecutionContext in open() for restart scenario.
     */
    private int currentIndexInPage;

    /**
     * Constructs TransactionReader with required TransactionRepository dependency.
     * 
     * @param transactionRepository Spring Data JPA repository for Transaction entity access
     */
    public TransactionReader(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Opens the ItemReader and initializes state.
     * 
     * Invoked by Spring Batch framework at start of step execution, before any
     * read() calls. Initializes pagination state and loads first page of data.
     * 
     * For new job execution (no restart):
     * - Sets currentPageNumber to 0
     * - Sets currentIndexInPage to 0
     * - Loads first page of transactions
     * 
     * For restart scenario (job previously failed and is being restarted):
     * - Retrieves currentPageNumber from ExecutionContext
     * - Retrieves currentIndexInPage from ExecutionContext
     * - Loads page at saved position
     * 
     * COBOL equivalent (CBTRN01C.cbl lines 252-268):
     * <pre>
     * 0000-DALYTRAN-OPEN.
     *     MOVE 8 TO APPL-RESULT.
     *     OPEN INPUT DALYTRAN-FILE
     *     IF  DALYTRAN-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE'
     *         PERFORM Z-ABEND-PROGRAM
     *     END-IF.
     * </pre>
     * 
     * @param executionContext Spring Batch execution context containing job state
     *                         for checkpoint/restart. May contain saved pagination
     *                         position if job is being restarted.
     * @throws ItemStreamException if database error occurs during initial page load
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            // Restore pagination state from ExecutionContext for restart scenario
            // If keys not present (new execution), getInt() returns 0 by default
            if (executionContext.containsKey(CURRENT_PAGE_KEY)) {
                currentPageNumber = executionContext.getInt(CURRENT_PAGE_KEY);
                log.info("Restarting TransactionReader from page {}", currentPageNumber);
            } else {
                currentPageNumber = 0;
                log.info("Starting TransactionReader from page 0");
            }

            if (executionContext.containsKey(CURRENT_INDEX_KEY)) {
                currentIndexInPage = executionContext.getInt(CURRENT_INDEX_KEY);
                log.info("Restarting TransactionReader from index {} within page", currentIndexInPage);
            } else {
                currentIndexInPage = 0;
            }

            // Load initial page (or restart page) from database
            loadPage(currentPageNumber);

            log.info("TransactionReader opened successfully. Total pages: {}, Total elements: {}",
                    currentPage != null ? currentPage.getTotalPages() : 0,
                    currentPage != null ? currentPage.getTotalElements() : 0);

        } catch (Exception e) {
            log.error("Error opening TransactionReader", e);
            throw new ItemStreamException("Failed to open TransactionReader and load initial page", e);
        }
    }

    /**
     * Reads next Transaction entity from current page.
     * 
     * Invoked repeatedly by Spring Batch framework to retrieve next item for processing.
     * Returns null when all records have been read (EOF condition), signaling framework
     * to complete the step.
     * 
     * Reading Algorithm:
     * 1. Check if current page is empty or null → return null (no data to read)
     * 2. Check if current index exceeds page size → load next page
     * 3. If next page is empty → return null (EOF reached)
     * 4. Return transaction at current index and increment index
     * 
     * COBOL equivalent (CBTRN01C.cbl lines 164-186):
     * <pre>
     * PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
     *     IF  END-OF-DAILY-TRANS-FILE = 'N'
     *         PERFORM 1000-DALYTRAN-GET-NEXT
     *         IF  END-OF-DAILY-TRANS-FILE = 'N'
     *             DISPLAY DALYTRAN-RECORD
     *             [Process transaction record...]
     *         END-IF
     *     END-IF
     * END-PERFORM.
     * </pre>
     * 
     * The null return value is equivalent to setting END-OF-DAILY-TRANS-FILE = 'Y'
     * in COBOL, which terminates the PERFORM UNTIL loop.
     * 
     * @return Next Transaction entity to process, or null if no more records (EOF)
     * @throws Exception if database error occurs during page load
     */
    @Override
    public Transaction read() throws Exception {
        // Check if current page is empty or null (no data loaded)
        if (currentPage == null || currentPage.isEmpty()) {
            log.debug("Current page is empty or null, returning null (EOF)");
            return null;
        }

        List<Transaction> content = currentPage.getContent();

        // Check if we've exhausted current page
        if (currentIndexInPage >= content.size()) {
            // Move to next page
            currentPageNumber++;
            currentIndexInPage = 0;

            log.debug("Loading next page: {}", currentPageNumber);
            loadPage(currentPageNumber);

            // Check if new page has content
            if (currentPage == null || currentPage.isEmpty()) {
                log.info("No more pages available, returning null (EOF). Total records processed from all pages.");
                return null;
            }

            content = currentPage.getContent();
        }

        // Return transaction at current index and increment index
        Transaction transaction = content.get(currentIndexInPage);
        currentIndexInPage++;

        log.trace("Read transaction: transId={}, index={}/{}, page={}/{}",
                transaction.getTransId(),
                currentIndexInPage,
                content.size(),
                currentPageNumber + 1,
                currentPage.getTotalPages());

        return transaction;
    }

    /**
     * Updates ExecutionContext with current pagination state for checkpoint/restart.
     * 
     * Invoked by Spring Batch framework after each chunk is successfully processed
     * (after writer completes). Saves current pagination position to ExecutionContext
     * so that if job fails and is restarted, reader can resume from last checkpoint.
     * 
     * Saves:
     * - currentPageNumber - which page we're currently reading
     * - currentIndexInPage - which record within page was last successfully processed
     * 
     * This enables checkpoint/restart capability, replicating COBOL job restart
     * functionality where batch jobs can be restarted from last checkpoint after
     * abnormal termination.
     * 
     * COBOL equivalent:
     * VSAM file positioning is maintained by system. In COBOL restart scenarios,
     * programs use checkpoint datasets to save file position. Spring Batch
     * ExecutionContext provides equivalent functionality automatically.
     * 
     * @param executionContext Spring Batch execution context to update with current state
     * @throws ItemStreamException if error occurs updating context (rare)
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        try {
            executionContext.putInt(CURRENT_PAGE_KEY, currentPageNumber);
            executionContext.putInt(CURRENT_INDEX_KEY, currentIndexInPage);

            log.trace("Updated ExecutionContext: page={}, index={}",
                    currentPageNumber, currentIndexInPage);

        } catch (Exception e) {
            log.error("Error updating ExecutionContext", e);
            throw new ItemStreamException("Failed to update ExecutionContext with pagination state", e);
        }
    }

    /**
     * Closes the ItemReader and releases resources.
     * 
     * Invoked by Spring Batch framework at end of step execution (success or failure),
     * after all read() calls complete. Cleans up reader state and releases references
     * to allow garbage collection.
     * 
     * Sets pagination state to null to release memory:
     * - currentPage - releases references to Transaction entities
     * - Resets page number and index counters
     * 
     * COBOL equivalent (CBTRN01C.cbl lines 188):
     * <pre>
     * PERFORM 9000-DALYTRAN-CLOSE.
     * ...
     * 9000-DALYTRAN-CLOSE.
     *     CLOSE DALYTRAN-FILE
     *     IF  DALYTRAN-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF.
     * </pre>
     * 
     * @throws ItemStreamException if error occurs during cleanup (rare)
     */
    @Override
    public void close() throws ItemStreamException {
        try {
            log.info("Closing TransactionReader. Final position: page={}, index={}",
                    currentPageNumber, currentIndexInPage);

            // Release references to allow garbage collection
            currentPage = null;
            currentPageNumber = 0;
            currentIndexInPage = 0;

            log.info("TransactionReader closed successfully");

        } catch (Exception e) {
            log.error("Error closing TransactionReader", e);
            throw new ItemStreamException("Failed to close TransactionReader", e);
        }
    }

    /**
     * Loads page of Transaction entities from database.
     * 
     * Private helper method to execute paginated database query using TransactionRepository.
     * Sorts results by primary key (trans_id) to maintain identical record order as
     * VSAM KSDS sequential access pattern from COBOL.
     * 
     * Query Configuration:
     * - Page number: Specified by pageNumber parameter (0-based)
     * - Page size: 1000 records (PAGE_SIZE constant)
     * - Sort order: trans_id ascending (primary key order)
     * 
     * Database Query:
     * <pre>
     * SELECT * FROM transaction
     * ORDER BY trans_id ASC
     * LIMIT 1000 OFFSET (pageNumber * 1000);
     * </pre>
     * 
     * Performance:
     * - Uses B-tree primary key index for efficient ordered retrieval
     * - LIMIT/OFFSET pagination suitable for sequential batch processing
     * - Page size 1000 balances database round-trips vs memory usage
     * 
     * VSAM Sequential Access Equivalent:
     * COBOL VSAM KSDS sequential reads automatically return records in primary
     * key order. This method replicates that behavior using SQL ORDER BY on
     * primary key column.
     * 
     * @param pageNumber Page number to load (0-based)
     */
    private void loadPage(int pageNumber) {
        try {
            // Create Pageable with page number, page size, and sort by primary key
            Pageable pageable = PageRequest.of(
                    pageNumber,
                    PAGE_SIZE,
                    Sort.by(Sort.Direction.ASC, "transId")
            );

            // Execute paginated query
            currentPage = transactionRepository.findAll(pageable);

            if (currentPage.hasContent()) {
                log.debug("Loaded page {} with {} transactions (total pages: {}, total elements: {})",
                        pageNumber,
                        currentPage.getNumberOfElements(),
                        currentPage.getTotalPages(),
                        currentPage.getTotalElements());
            } else {
                log.debug("Page {} is empty (no more data)", pageNumber);
            }

        } catch (Exception e) {
            log.error("Error loading page {} from database", pageNumber, e);
            throw new ItemStreamException(
                    String.format("Failed to load page %d from database", pageNumber), e);
        }
    }
}
