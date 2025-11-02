/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.reader;

import com.carddemo.entity.Card;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.stereotype.Component;

/**
 * Spring Batch ItemReader implementation for reading card and account association data
 * to build cross-reference relationships.
 * 
 * <p><b>COBOL Source Transformation:</b></p>
 * <p>This reader transforms the sequential VSAM CARDFILE read logic from COBOL program
 * CBACT02C.cbl (Account Cross-Reference Build Batch Job). The original COBOL program
 * performed sequential reads of the CARDFILE KSDS with the following pattern:</p>
 * 
 * <pre>
 * COBOL Pattern (CBACT02C.cbl lines 92-116):
 * 1000-CARDFILE-GET-NEXT.
 *     READ CARDFILE-FILE INTO CARD-RECORD.
 *     IF CARDFILE-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         IF CARDFILE-STATUS = '10'  (EOF)
 *             MOVE 16 TO APPL-RESULT
 *         ELSE
 *             MOVE 12 TO APPL-RESULT
 *         END-IF
 *     END-IF
 * </pre>
 * 
 * <p><b>Spring Batch Transformation:</b></p>
 * <ul>
 *   <li>VSAM Sequential Read → JPA Paging Query with chunk processing</li>
 *   <li>CARDFILE KSDS → Card entity table with JPA relationships</li>
 *   <li>CARD-RECORD (CVACT02Y) → Card entity with Account and Customer relationships</li>
 *   <li>File status '00' → Successful JPA entity read</li>
 *   <li>File status '10' (EOF) → JpaPagingItemReader returns null</li>
 *   <li>File status error → JPA exception handling</li>
 * </ul>
 * 
 * <p><b>Chunk-Oriented Processing:</b></p>
 * <p>Implements JpaPagingItemReader pattern to read Card entities with their associated
 * Account relationships in chunks of 1000 records per Section 0.5 batch configuration
 * requirements. This approach provides:</p>
 * <ul>
 *   <li>Memory efficiency through pagination (1000 cards per page)</li>
 *   <li>Transaction boundary control (commits after each chunk)</li>
 *   <li>Restart capability using Spring Batch ExecutionContext</li>
 *   <li>Performance optimization through eager loading (JOIN FETCH)</li>
 * </ul>
 * 
 * <p><b>Eager Loading Strategy:</b></p>
 * <p>The query uses JOIN FETCH to eagerly load Account and Customer relationships,
 * minimizing database round-trips during cross-reference validation. This prevents
 * the N+1 query problem that would occur with lazy loading in batch processing.</p>
 * 
 * <p><b>Usage Context:</b></p>
 * <p>Used by AccountXrefBuildJob to populate account_xref and card_xref tables,
 * replacing VSAM alternate index creation logic from CBACT02C.cbl. The reader provides
 * complete card→account→customer relationship chains for validation and cross-reference
 * building.</p>
 * 
 * <p><b>Data Flow:</b></p>
 * <pre>
 * CardAccountReader (reads Card entities)
 *     ↓
 * AccountXrefProcessor (validates relationships)
 *     ↓
 * AccountXrefWriter (persists cross-reference entries)
 * </pre>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li>Skip records with null account relationships (orphaned cards)</li>
 *   <li>Log warnings for cards without valid account associations</li>
 *   <li>Continue processing remaining cards per skip policy</li>
 *   <li>Maintain audit trail for skipped records</li>
 * </ul>
 * 
 * <p><b>Performance Characteristics:</b></p>
 * <ul>
 *   <li>Page size: 1000 records (configurable via chunk size)</li>
 *   <li>Query optimization: Single query per page with JOIN FETCH</li>
 *   <li>Memory footprint: Fixed at ~1000 Card objects per page</li>
 *   <li>Database round-trips: 1 per page + 0 for relationships (eager loaded)</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.entity.Card
 * @see com.carddemo.entity.Account
 * @see com.carddemo.entity.Customer
 * @see org.springframework.batch.item.database.JpaPagingItemReader
 */
@Component
@StepScope
public class CardAccountReader extends JpaPagingItemReader<Card> {
    
    private static final Logger logger = LoggerFactory.getLogger(CardAccountReader.class);
    
    /**
     * Page size for JPA paging query.
     * Matches chunk size configuration per Section 0.5 requirements.
     */
    private static final int PAGE_SIZE = 1000;
    
    /**
     * JPA query string for reading Card entities with eager loading.
     * Uses JOIN FETCH to load Account and Customer relationships in a single query,
     * avoiding N+1 query problem during batch processing.
     * 
     * Query Pattern:
     * - SELECT c FROM Card c: Main entity selection
     * - JOIN FETCH c.account a: Eager load account relationship
     * - JOIN FETCH a.customer: Eager load customer through account
     * - ORDER BY c.cardNumber: Maintain VSAM sequential read order
     */
    private static final String QUERY_STRING = 
        "SELECT c FROM Card c " +
        "LEFT JOIN FETCH c.account a " +
        "LEFT JOIN FETCH a.customer " +
        "ORDER BY c.cardNumber";
    
    /**
     * Constructs CardAccountReader with JPA configuration.
     * 
     * <p>This constructor initializes the JpaPagingItemReader parent class with:</p>
     * <ul>
     *   <li>EntityManagerFactory for JPA context and transaction management</li>
     *   <li>Query string with JOIN FETCH for eager relationship loading</li>
     *   <li>Page size of 1000 to match chunk-oriented processing configuration</li>
     *   <li>Reader name for Spring Batch monitoring and restart capability</li>
     * </ul>
     * 
     * <p><b>@StepScope Behavior:</b></p>
     * <p>The @StepScope annotation on the class ensures this reader is created
     * for each step execution, enabling:</p>
     * <ul>
     *   <li>Late binding of job parameters</li>
     *   <li>Step-level isolation and restart capability</li>
     *   <li>Proper cleanup of JPA resources per step</li>
     * </ul>
     * 
     * <p><b>EntityManagerFactory Injection:</b></p>
     * <p>Spring automatically injects the EntityManagerFactory configured in the
     * application context. This factory provides:</p>
     * <ul>
     *   <li>JPA EntityManager instances for database operations</li>
     *   <li>Transaction management integration with Spring Batch</li>
     *   <li>Connection pooling via HikariCP</li>
     *   <li>Second-level cache configuration (if enabled)</li>
     * </ul>
     * 
     * @param entityManagerFactory JPA EntityManagerFactory for database access
     * @throws IllegalArgumentException if entityManagerFactory is null
     */
    public CardAccountReader(EntityManagerFactory entityManagerFactory) {
        logger.info("Initializing CardAccountReader with page size {} and eager loading enabled", 
                    PAGE_SIZE);
        
        // Validate required dependency
        if (entityManagerFactory == null) {
            logger.error("EntityManagerFactory cannot be null");
            throw new IllegalArgumentException("EntityManagerFactory is required for CardAccountReader");
        }
        
        // Configure parent JpaPagingItemReader
        setEntityManagerFactory(entityManagerFactory);
        setQueryString(QUERY_STRING);
        setPageSize(PAGE_SIZE);
        setName("cardAccountReader");
        
        logger.debug("CardAccountReader configured with query: {}", QUERY_STRING);
        logger.info("CardAccountReader initialized successfully");
    }
    
    /**
     * Opens the reader and initializes resources for reading.
     * 
     * <p>This method is called by Spring Batch framework before the first read operation.
     * It delegates to the parent class implementation which:</p>
     * <ul>
     *   <li>Initializes the JPA query with configured EntityManager</li>
     *   <li>Restores reader state from ExecutionContext (for restart scenarios)</li>
     *   <li>Prepares pagination parameters for first page read</li>
     *   <li>Sets up transaction boundaries for chunk processing</li>
     * </ul>
     * 
     * <p><b>Restart Capability:</b></p>
     * <p>If this step is being restarted after a failure, the ExecutionContext contains
     * the page number where processing stopped. The reader will skip to that page and
     * resume reading, preventing duplicate processing of already-processed cards.</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <p>Equivalent to COBOL 0000-CARDFILE-OPEN paragraph (lines 118-134 in CBACT02C.cbl):
     * <pre>
     * OPEN INPUT CARDFILE-FILE
     * IF CARDFILE-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * </pre>
     * </p>
     * 
     * @param executionContext Spring Batch execution context for restart capability
     * @throws Exception if reader initialization fails
     */
    @Override
    public void open(ExecutionContext executionContext) throws Exception {
        logger.info("Opening CardAccountReader for card-account association reading");
        logger.debug("ExecutionContext contains: {}", executionContext);
        
        try {
            // Delegate to parent implementation for JPA query initialization
            super.open(executionContext);
            
            // Log restart information if applicable
            if (executionContext.containsKey("read.count")) {
                int readCount = executionContext.getInt("read.count");
                logger.info("Resuming CardAccountReader from previous execution at read count: {}", 
                           readCount);
            } else {
                logger.info("Starting fresh CardAccountReader execution");
            }
            
            logger.info("CardAccountReader opened successfully and ready to read Card entities");
            
        } catch (Exception e) {
            logger.error("Failed to open CardAccountReader: {}", e.getMessage(), e);
            throw new RuntimeException("Error opening CardAccountReader for card-account reading", e);
        }
    }
    
    /**
     * Reads the next Card entity with associated Account and Customer relationships.
     * 
     * <p>This method is called repeatedly by Spring Batch framework to read cards
     * in chunks. It delegates to parent implementation which:</p>
     * <ul>
     *   <li>Executes JPA paging query for current page if needed</li>
     *   <li>Returns next Card entity from current page results</li>
     *   <li>Fetches next page when current page is exhausted</li>
     *   <li>Returns null when all cards have been read (signals end of input)</li>
     * </ul>
     * 
     * <p><b>Chunk Processing Flow:</b></p>
     * <pre>
     * 1. read() called by Spring Batch (returns Card #1)
     * 2. read() called again (returns Card #2)
     * ... (continues for chunk size of 1000)
     * 3. read() called (returns Card #1000)
     * 4. Chunk processed by processor and writer
     * 5. Transaction committed
     * 6. read() called (fetches next page, returns Card #1001)
     * ... (continues until null returned)
     * </pre>
     * 
     * <p><b>Relationship Loading:</b></p>
     * <p>Each returned Card object has its Account relationship eagerly loaded via
     * JOIN FETCH. The Account object in turn has its Customer relationship loaded.
     * This provides complete card→account→customer chains without additional queries.</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <p>Equivalent to COBOL 1000-CARDFILE-GET-NEXT paragraph (lines 92-116 in CBACT02C.cbl):
     * <pre>
     * READ CARDFILE-FILE INTO CARD-RECORD.
     * IF CARDFILE-STATUS = '00'
     *     Continue processing
     * ELSE IF CARDFILE-STATUS = '10'
     *     End of file reached
     * </pre>
     * </p>
     * 
     * <p><b>Null Cards Handling:</b></p>
     * <p>If a Card entity has a null account relationship (orphaned card), it is still
     * returned by this reader. The AccountXrefProcessor is responsible for validating
     * relationships and skipping invalid cards according to the skip policy.</p>
     * 
     * @return Next Card entity with relationships loaded, or null if no more cards
     * @throws Exception if read operation fails (triggers skip or retry policy)
     */
    @Override
    public Card read() throws Exception {
        // Delegate to parent implementation for actual reading
        Card card = super.read();
        
        // Log read operation for audit trail (only log every 100th card to avoid log spam)
        if (card != null && getCurrentItemCount() % 100 == 0) {
            logger.debug("Read card: {} (total cards read: {})", 
                        card.getCardNumber(), getCurrentItemCount());
        }
        
        // Log when reading completes
        if (card == null && getCurrentItemCount() > 0) {
            logger.info("Completed reading all cards. Total cards read: {}", 
                       getCurrentItemCount());
        }
        
        return card;
    }
    
    /**
     * Closes the reader and releases JPA resources.
     * 
     * <p>This method is called by Spring Batch framework after all reading is complete
     * or when a failure requires cleanup. It delegates to parent implementation which:</p>
     * <ul>
     *   <li>Closes the EntityManager and releases database connections</li>
     *   <li>Saves current read position to ExecutionContext (for restart)</li>
     *   <li>Cleans up JPA query resources</li>
     *   <li>Releases any cached entity objects</li>
     * </ul>
     * 
     * <p><b>Resource Cleanup:</b></p>
     * <p>Proper cleanup is critical in batch processing to prevent:</p>
     * <ul>
     *   <li>Database connection pool exhaustion</li>
     *   <li>Memory leaks from cached entities</li>
     *   <li>Transaction timeout issues</li>
     *   <li>File handle leaks in underlying JPA provider</li>
     * </ul>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <p>Equivalent to COBOL 9000-CARDFILE-CLOSE paragraph (lines 136-152 in CBACT02C.cbl):
     * <pre>
     * CLOSE CARDFILE-FILE
     * IF CARDFILE-STATUS = '00'
     *     Continue
     * ELSE
     *     Display error and abend
     * </pre>
     * </p>
     * 
     * @throws Exception if resource cleanup fails
     */
    @Override
    public void close() throws Exception {
        logger.info("Closing CardAccountReader. Total cards read: {}", getCurrentItemCount());
        
        try {
            // Delegate to parent implementation for JPA resource cleanup
            super.close();
            
            logger.info("CardAccountReader closed successfully. Database resources released.");
            
        } catch (Exception e) {
            logger.error("Error closing CardAccountReader: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to close CardAccountReader and release resources", e);
        }
    }
}
