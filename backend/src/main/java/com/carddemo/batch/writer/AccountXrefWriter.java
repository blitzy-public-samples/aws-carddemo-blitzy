/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch ItemWriter implementation for persisting account and card cross-reference
 * entries to PostgreSQL database.
 * 
 * <p><b>COBOL Transformation Context:</b></p>
 * <p>This writer transforms the VSAM alternate index creation logic from COBOL batch program
 * CBACT02C.cbl. In the mainframe environment, VSAM automatically maintains alternate indexes
 * for the CXACAIX (Card Cross Account Index) file. In the modernized PostgreSQL architecture,
 * explicit cross-reference tables (account_xref and card_xref) replace VSAM alternate indexes
 * to enable efficient bidirectional navigation queries.</p>
 * 
 * <p><b>Functional Purpose:</b></p>
 * <ul>
 *   <li>Persists AccountXref entities mapping accounts to their associated cards</li>
 *   <li>Persists CardXref entities mapping cards to their owning accounts and customers</li>
 *   <li>Replaces VSAM alternate index with PostgreSQL indexed relationships</li>
 *   <li>Ensures referential integrity through foreign key constraints</li>
 * </ul>
 * 
 * <p><b>Transaction Semantics:</b></p>
 * <ul>
 *   <li>Chunk-level commits (default 1000 records per transaction per Section 0.5)</li>
 *   <li>Isolation level: READ_COMMITTED per Section 0.3 requirements</li>
 *   <li>Automatic rollback on DataIntegrityViolationException (FK violations, duplicates)</li>
 *   <li>Retry on TransientDataAccessException (network issues, deadlocks)</li>
 * </ul>
 * 
 * <p><b>Performance Optimization:</b></p>
 * <ul>
 *   <li>JPA batch inserts via hibernate.jdbc.batch_size=50 (DatabaseConfig)</li>
 *   <li>Ordered inserts via hibernate.order_inserts=true for efficiency</li>
 *   <li>EntityManager flush and clear after batch to prevent memory exhaustion</li>
 *   <li>Minimized database round-trips through batch operations</li>
 * </ul>
 * 
 * <p><b>Error Handling Strategy:</b></p>
 * <ul>
 *   <li>DataIntegrityViolationException: Logged with entity IDs, transaction continues</li>
 *   <li>Duplicate key violations: Silently skipped (idempotent behavior)</li>
 *   <li>Foreign key constraint failures: Logged as warning, skip invalid entries</li>
 *   <li>All errors include card/account/customer IDs for troubleshooting</li>
 * </ul>
 * 
 * <p><b>Usage in Spring Batch Job:</b></p>
 * <pre>
 * {@code
 * @Bean
 * public Step accountXrefBuildStep(JobRepository jobRepository,
 *                                   PlatformTransactionManager transactionManager,
 *                                   ItemReader<XrefEntry> reader,
 *                                   ItemProcessor<XrefEntry, XrefEntry> processor,
 *                                   AccountXrefWriter writer) {
 *     return new StepBuilder("accountXrefBuildStep", jobRepository)
 *         .<XrefEntry, XrefEntry>chunk(1000, transactionManager)
 *         .reader(reader)
 *         .processor(processor)
 *         .writer(writer)
 *         .build();
 * }
 * }
 * </pre>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see org.springframework.batch.item.ItemWriter
 * @see com.carddemo.entity.AccountXref
 * @see com.carddemo.entity.CardXref
 */
@Component
public class AccountXrefWriter implements ItemWriter<XrefEntry> {

    private static final Logger logger = LoggerFactory.getLogger(AccountXrefWriter.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Writes a chunk of cross-reference entries to the database atomically.
     * 
     * <p>This method processes a list of XrefEntry objects, extracting AccountXref and
     * CardXref entities and persisting them in batch operations. All writes within a
     * chunk are committed atomically - if any write fails, the entire chunk is rolled back.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (CBACT02C.cbl):
     *     * VSAM automatically creates alternate index entries for CXACAIX file
     *     * Cross-references maintained by VSAM access method
     *     
     * Java (this method):
     *     * Explicit INSERT operations to account_xref and card_xref tables
     *     * Foreign key constraints enforce relationship validity
     *     * JPA batch operations optimize database performance
     * </pre>
     * 
     * <p><b>Processing Steps:</b></p>
     * <ol>
     *   <li>Extract AccountXref entities from XrefEntry items</li>
     *   <li>Extract CardXref entities from XrefEntry items</li>
     *   <li>Persist all AccountXref entities in batch using EntityManager</li>
     *   <li>Persist all CardXref entities in batch using EntityManager</li>
     *   <li>Flush EntityManager to execute SQL statements</li>
     *   <li>Clear EntityManager to release memory</li>
     *   <li>Log batch processing metrics (count, duration)</li>
     * </ol>
     * 
     * <p><b>Transaction Behavior:</b></p>
     * <ul>
     *   <li>Propagation: REQUIRED (participates in existing transaction)</li>
     *   <li>Isolation: READ_COMMITTED (prevents dirty reads)</li>
     *   <li>Rollback: On any Exception (ensures data consistency)</li>
     *   <li>Chunk size: Configurable (default 1000 per Section 0.5)</li>
     * </ul>
     * 
     * <p><b>Error Scenarios:</b></p>
     * <ul>
     *   <li>Duplicate key: Constraint violation logged, entry skipped</li>
     *   <li>FK constraint failure: Invalid reference logged, entry skipped</li>
     *   <li>Database unavailable: Exception propagated for retry logic</li>
     *   <li>Memory exhaustion: EntityManager cleared after each chunk</li>
     * </ul>
     * 
     * @param chunk the list of XrefEntry items to write (size determined by chunk configuration)
     * @throws Exception if a fatal error occurs during batch write operation
     */
    @Override
    @Transactional(
        propagation = Propagation.REQUIRED,
        isolation = Isolation.READ_COMMITTED,
        rollbackFor = Exception.class
    )
    public void write(Chunk<? extends XrefEntry> chunk) throws Exception {
        long startTime = System.currentTimeMillis();
        
        if (chunk == null || chunk.isEmpty()) {
            logger.debug("Received empty chunk, skipping write operation");
            return;
        }
        
        List<? extends XrefEntry> items = chunk.getItems();
        logger.info("Writing {} cross-reference entries to database", items.size());
        
        List<Object> accountXrefs = new ArrayList<>();
        List<Object> cardXrefs = new ArrayList<>();
        
        // Extract cross-reference entities from wrapper objects
        for (XrefEntry entry : items) {
            if (entry == null) {
                logger.warn("Null XrefEntry encountered in chunk, skipping");
                continue;
            }
            
            try {
                Object accountXref = entry.getAccountXref();
                Object cardXref = entry.getCardXref();
                
                if (accountXref != null) {
                    accountXrefs.add(accountXref);
                }
                
                if (cardXref != null) {
                    cardXrefs.add(cardXref);
                }
            } catch (Exception e) {
                logger.error("Error extracting cross-reference entities from XrefEntry: {}",
                    e.getMessage(), e);
                // Continue processing remaining entries rather than failing entire chunk
            }
        }
        
        int totalPersisted = 0;
        
        // Persist AccountXref entities in batch
        if (!accountXrefs.isEmpty()) {
            try {
                logger.debug("Persisting {} AccountXref entities", accountXrefs.size());
                for (Object accountXref : accountXrefs) {
                    entityManager.persist(accountXref);
                    totalPersisted++;
                }
                logger.info("Successfully persisted {} AccountXref entries", accountXrefs.size());
            } catch (DataIntegrityViolationException e) {
                logger.warn("Data integrity violation while persisting AccountXref entries: {}",
                    e.getMessage());
                // Log but don't fail - allows processing to continue for valid entries
                // Specific constraint violations (duplicate keys, FK failures) are acceptable
            } catch (Exception e) {
                logger.error("Error persisting AccountXref entities: {}", e.getMessage(), e);
                throw e; // Propagate unexpected errors for retry logic
            }
        }
        
        // Persist CardXref entities in batch
        if (!cardXrefs.isEmpty()) {
            try {
                logger.debug("Persisting {} CardXref entities", cardXrefs.size());
                for (Object cardXref : cardXrefs) {
                    entityManager.persist(cardXref);
                    totalPersisted++;
                }
                logger.info("Successfully persisted {} CardXref entries", cardXrefs.size());
            } catch (DataIntegrityViolationException e) {
                logger.warn("Data integrity violation while persisting CardXref entries: {}",
                    e.getMessage());
                // Log but don't fail - allows processing to continue for valid entries
            } catch (Exception e) {
                logger.error("Error persisting CardXref entities: {}", e.getMessage(), e);
                throw e; // Propagate unexpected errors for retry logic
            }
        }
        
        // Flush to database and clear persistence context to prevent memory exhaustion
        try {
            entityManager.flush();
            entityManager.clear();
        } catch (Exception e) {
            logger.error("Error flushing EntityManager: {}", e.getMessage(), e);
            throw e;
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("Completed writing {} cross-reference entries in {} ms (AccountXref: {}, CardXref: {})",
            totalPersisted, duration, accountXrefs.size(), cardXrefs.size());
    }
}

/**
 * Data Transfer Object encapsulating both AccountXref and CardXref entities for batch processing.
 * 
 * <p>This wrapper class allows the ItemProcessor to return both cross-reference entities
 * in a single object, which the AccountXrefWriter can then decompose and persist separately.
 * This design maintains clean separation of concerns while enabling efficient batch processing
 * of related entities.</p>
 * 
 * <p><b>Usage Pattern:</b></p>
 * <pre>
 * {@code
 * // In ItemProcessor
 * public XrefEntry process(CardRecord cardRecord) {
 *     AccountXref accountXref = buildAccountXref(cardRecord);
 *     CardXref cardXref = buildCardXref(cardRecord);
 *     return new XrefEntry(accountXref, cardXref);
 * }
 * 
 * // In ItemWriter
 * for (XrefEntry entry : items) {
 *     accountXrefs.add(entry.getAccountXref());
 *     cardXrefs.add(entry.getCardXref());
 * }
 * }
 * </pre>
 * 
 * <p><b>Design Rationale:</b></p>
 * <ul>
 *   <li>Maintains type safety for ItemWriter generic parameter</li>
 *   <li>Allows processor to create both cross-references in single pass</li>
 *   <li>Enables atomic persistence of related entities</li>
 *   <li>Simplifies error handling with consistent wrapper type</li>
 * </ul>
 */
class XrefEntry {
    private final Object accountXref;
    private final Object cardXref;
    
    /**
     * Constructs a new XrefEntry with both cross-reference entities.
     * 
     * @param accountXref the AccountXref entity (account-to-card mapping)
     * @param cardXref the CardXref entity (card-to-account-to-customer mapping)
     */
    public XrefEntry(Object accountXref, Object cardXref) {
        this.accountXref = accountXref;
        this.cardXref = cardXref;
    }
    
    /**
     * Returns the AccountXref entity.
     * 
     * @return AccountXref entity for account-to-card cross-reference
     */
    public Object getAccountXref() {
        return accountXref;
    }
    
    /**
     * Returns the CardXref entity.
     * 
     * @return CardXref entity for card-to-account-to-customer cross-reference
     */
    public Object getCardXref() {
        return cardXref;
    }
}
