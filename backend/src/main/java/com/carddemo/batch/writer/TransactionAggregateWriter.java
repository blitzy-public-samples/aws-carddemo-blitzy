/*
 * TransactionAggregateWriter.java
 *
 * Spring Batch ItemWriter implementation for persisting TransactionAggregate entities.
 * Transforms COBOL VSAM transaction category balance update logic to modern Spring Batch
 * chunk-oriented processing with UPSERT semantics for idempotent batch job execution.
 *
 * Migration Notes:
 * - COBOL Source: CBTRN03C.cbl transaction processing patterns
 * - Replaces: VSAM REWRITE operations with JPA repository saves
 * - Transaction: Chunk-level commits per Spring Batch framework
 * - Isolation: READ_COMMITTED per Section 0.3 requirements
 *
 * Copyright (c) 2024 CardDemo Application
 * Licensed under the Apache License, Version 2.0
 */
package com.carddemo.batch.writer;

import com.carddemo.entity.TransactionAggregate;
import com.carddemo.repository.TransactionAggregateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spring Batch ItemWriter for persisting TransactionAggregate entities to the database.
 * 
 * This writer implements chunk-oriented processing with UPSERT logic to ensure idempotent
 * batch job execution. Transaction aggregates are either inserted (if new) or updated
 * (if existing) based on the composite key of accountId, transactionTypeCode, and
 * transactionCategoryCode.
 * 
 * <p>Key Features:</p>
 * <ul>
 *   <li>UPSERT semantics: INSERT new aggregates or UPDATE existing ones</li>
 *   <li>Chunk-level transaction boundaries matching Spring Batch framework</li>
 *   <li>BigDecimal precision preservation for financial calculations</li>
 *   <li>Comprehensive error handling with logging</li>
 *   <li>Audit trail with created/updated timestamps</li>
 * </ul>
 * 
 * <p>Transaction Management:</p>
 * <ul>
 *   <li>Isolation Level: READ_COMMITTED (prevents dirty reads)</li>
 *   <li>Propagation: REQUIRED (participates in existing chunk transaction)</li>
 *   <li>Rollback: Automatic on any exception</li>
 * </ul>
 * 
 * <p>Performance Considerations:</p>
 * <ul>
 *   <li>Batch size: Configured at job level (typically 1000 records)</li>
 *   <li>Hibernate batch inserts: spring.jpa.properties.hibernate.jdbc.batch_size</li>
 *   <li>Second-level cache disabled for accurate aggregation results</li>
 * </ul>
 * 
 * @see org.springframework.batch.item.ItemWriter
 * @see com.carddemo.entity.TransactionAggregate
 * @see com.carddemo.repository.TransactionAggregateRepository
 */
@Component
public class TransactionAggregateWriter implements ItemWriter<TransactionAggregate> {

    private static final Logger logger = LoggerFactory.getLogger(TransactionAggregateWriter.class);

    private final TransactionAggregateRepository transactionAggregateRepository;

    /**
     * Constructs a new TransactionAggregateWriter with required dependencies.
     *
     * @param transactionAggregateRepository JPA repository for TransactionAggregate persistence
     */
    public TransactionAggregateWriter(TransactionAggregateRepository transactionAggregateRepository) {
        this.transactionAggregateRepository = transactionAggregateRepository;
        logger.info("TransactionAggregateWriter initialized successfully");
    }

    /**
     * Writes a chunk of TransactionAggregate entities to the database using UPSERT logic.
     * 
     * This method processes each aggregate in the chunk and either inserts a new record
     * or updates an existing one based on the composite business key. The operation is
     * idempotent, allowing safe re-execution of failed chunks.
     * 
     * <p>UPSERT Strategy:</p>
     * <ol>
     *   <li>Query for existing aggregate by composite key</li>
     *   <li>If exists: Update balance, count, and timestamp</li>
     *   <li>If not exists: Insert new aggregate with creation timestamp</li>
     * </ol>
     * 
     * <p>Error Handling:</p>
     * <ul>
     *   <li>DataIntegrityViolationException: Logged and item skipped (duplicate key race condition)</li>
     *   <li>Other exceptions: Propagated to Spring Batch for retry/skip policy handling</li>
     * </ul>
     * 
     * @param chunk the chunk of TransactionAggregate items to write (not null)
     * @throws Exception if a non-recoverable error occurs during write operation
     */
    @Override
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public void write(Chunk<? extends TransactionAggregate> chunk) throws Exception {
        List<? extends TransactionAggregate> aggregates = chunk.getItems();
        
        if (aggregates == null || aggregates.isEmpty()) {
            logger.debug("Received empty chunk, skipping write operation");
            return;
        }

        logger.info("Writing chunk of {} transaction aggregates", aggregates.size());
        
        int insertCount = 0;
        int updateCount = 0;
        int skipCount = 0;

        List<TransactionAggregate> toSave = new ArrayList<>();

        for (TransactionAggregate aggregate : aggregates) {
            try {
                // Validate aggregate data before processing
                if (!isValidAggregate(aggregate)) {
                    logger.warn("Invalid aggregate data: accountId={}, typeCode={}, categoryCode={} - skipping",
                        aggregate.getAccountId(), 
                        aggregate.getTransactionTypeCode(),
                        aggregate.getTransactionCategoryCode());
                    skipCount++;
                    continue;
                }

                // UPSERT logic: Check if aggregate already exists
                Optional<TransactionAggregate> existingOpt = transactionAggregateRepository
                    .findByAccountIdAndTransactionTypeCodeAndTransactionCategoryCode(
                        aggregate.getAccountId(),
                        aggregate.getTransactionTypeCode(),
                        aggregate.getTransactionCategoryCode()
                    );

                if (existingOpt.isPresent()) {
                    // UPDATE existing aggregate
                    TransactionAggregate existing = existingOpt.get();
                    
                    // Preserve entity ID and creation timestamp
                    aggregate.setId(existing.getId());
                    aggregate.setCreatedDate(existing.getCreatedDate());
                    
                    // Update modification timestamp
                    aggregate.setUpdatedDate(LocalDateTime.now());
                    
                    // Validate BigDecimal precision for financial amounts
                    if (aggregate.getCategoryBalance() != null) {
                        aggregate.setCategoryBalance(
                            aggregate.getCategoryBalance().setScale(2, RoundingMode.HALF_UP)
                        );
                    }
                    
                    logger.debug("Updating existing aggregate: id={}, accountId={}, balance={}",
                        existing.getId(),
                        aggregate.getAccountId(),
                        aggregate.getCategoryBalance());
                    
                    updateCount++;
                } else {
                    // INSERT new aggregate
                    aggregate.setCreatedDate(LocalDateTime.now());
                    aggregate.setUpdatedDate(LocalDateTime.now());
                    
                    // Validate BigDecimal precision for financial amounts
                    if (aggregate.getCategoryBalance() != null) {
                        aggregate.setCategoryBalance(
                            aggregate.getCategoryBalance().setScale(2, RoundingMode.HALF_UP)
                        );
                    }
                    
                    logger.debug("Inserting new aggregate: accountId={}, typeCode={}, categoryCode={}, balance={}",
                        aggregate.getAccountId(),
                        aggregate.getTransactionTypeCode(),
                        aggregate.getTransactionCategoryCode(),
                        aggregate.getCategoryBalance());
                    
                    insertCount++;
                }

                toSave.add(aggregate);

            } catch (DataIntegrityViolationException e) {
                // Handle duplicate key race condition (multiple threads/processes)
                logger.warn("Data integrity violation for aggregate: accountId={}, typeCode={}, categoryCode={} - skipping",
                    aggregate.getAccountId(),
                    aggregate.getTransactionTypeCode(),
                    aggregate.getTransactionCategoryCode(),
                    e);
                skipCount++;
            }
        }

        // Batch save all aggregates in a single database round-trip
        if (!toSave.isEmpty()) {
            try {
                transactionAggregateRepository.saveAll(toSave);
                transactionAggregateRepository.flush(); // Force immediate persistence
                
                logger.info("Successfully persisted {} aggregates (inserts: {}, updates: {}, skipped: {})",
                    toSave.size(), insertCount, updateCount, skipCount);
                    
            } catch (Exception e) {
                logger.error("Failed to persist transaction aggregates batch", e);
                throw e; // Propagate to Spring Batch for retry/skip handling
            }
        } else {
            logger.warn("No valid aggregates to persist in this chunk (skipped: {})", skipCount);
        }
    }

    /**
     * Validates that a TransactionAggregate entity has all required fields populated.
     * 
     * <p>Validation Rules:</p>
     * <ul>
     *   <li>Account ID must not be null</li>
     *   <li>Transaction type code must not be null or empty</li>
     *   <li>Transaction category code must not be null</li>
     *   <li>Category balance must not be null</li>
     *   <li>Transaction count must be non-negative</li>
     * </ul>
     *
     * @param aggregate the TransactionAggregate to validate
     * @return true if aggregate is valid, false otherwise
     */
    private boolean isValidAggregate(TransactionAggregate aggregate) {
        if (aggregate == null) {
            return false;
        }

        if (aggregate.getAccountId() == null) {
            logger.warn("Aggregate validation failed: accountId is null");
            return false;
        }

        if (aggregate.getTransactionTypeCode() == null || aggregate.getTransactionTypeCode().trim().isEmpty()) {
            logger.warn("Aggregate validation failed: transactionTypeCode is null or empty");
            return false;
        }

        if (aggregate.getTransactionCategoryCode() == null) {
            logger.warn("Aggregate validation failed: transactionCategoryCode is null");
            return false;
        }

        if (aggregate.getCategoryBalance() == null) {
            logger.warn("Aggregate validation failed: categoryBalance is null");
            return false;
        }

        if (aggregate.getTransactionCount() != null && aggregate.getTransactionCount() < 0) {
            logger.warn("Aggregate validation failed: transactionCount is negative: {}", 
                aggregate.getTransactionCount());
            return false;
        }

        return true;
    }
}
