/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.writer;

import com.carddemo.entity.Card;
import com.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.ValidationException;
import java.sql.SQLException;
import java.util.List;

/**
 * Spring Batch ItemWriter implementation for persisting Card entities to PostgreSQL database.
 * 
 * <p>This class replaces COBOL WRITE operations to CARDDAT VSAM in the CBCRD01C batch program.
 * It implements chunk-oriented processing with batch insert/update operations using Spring Data JPA.
 * 
 * <p>Key Features:
 * <ul>
 *   <li>Batch persistence using JPA repository saveAll() method</li>
 *   <li>Transaction management handled by Spring Batch step configuration</li>
 *   <li>EntityManager flush/clear for memory optimization</li>
 *   <li>Comprehensive error handling for data integrity violations</li>
 *   <li>Operational monitoring via SLF4J logging</li>
 *   <li>Foreign key validation for card-to-account relationships</li>
 * </ul>
 * 
 * <p>Transaction Configuration:
 * <ul>
 *   <li>Transactions managed at Spring Batch step level (not at writer level)</li>
 *   <li>Chunk size: 1000 records per transaction (per Section 0.5)</li>
 *   <li>Automatic rollback on exception handled by Spring Batch</li>
 * </ul>
 * 
 * <p>Performance Optimization:
 * <ul>
 *   <li>Batch insert/update via saveAll() reduces round-trips</li>
 *   <li>EntityManager flush() commits pending changes</li>
 *   <li>EntityManager clear() releases memory after batch</li>
 *   <li>HikariCP connection pooling (20-50 connections)</li>
 * </ul>
 * 
 * @see Card
 * @see CardRepository
 * @see org.springframework.batch.item.ItemWriter
 * @since 1.0
 */
@Component
public class CardItemWriter implements ItemWriter<Card> {

    private static final Logger logger = LoggerFactory.getLogger(CardItemWriter.class);

    private final CardRepository cardRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Constructs a new CardItemWriter with required dependencies.
     * 
     * @param cardRepository the JPA repository for Card entity persistence
     */
    public CardItemWriter(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Writes a chunk of Card entities to the PostgreSQL database.
     * 
     * <p>This method replaces COBOL WRITE operations to CARDDAT VSAM. It persists
     * a batch of card records using JPA saveAll() for optimal performance.
     * 
     * <p>Processing Flow:
     * <ol>
     *   <li>Validate input chunk is not null or empty</li>
     *   <li>Persist all cards using repository saveAll()</li>
     *   <li>Flush EntityManager to commit pending changes</li>
     *   <li>Clear EntityManager to release memory</li>
     *   <li>Log batch metrics for monitoring</li>
     * </ol>
     * 
     * <p>Transaction Semantics:
     * <ul>
     *   <li>Entire chunk commits or rolls back as atomic unit (managed by Spring Batch)</li>
     *   <li>Transaction isolation level configured at batch step level</li>
     *   <li>Automatic rollback on any exception handled by Spring Batch</li>
     * </ul>
     * 
     * <p>Error Handling:
     * <ul>
     *   <li>DataIntegrityViolationException: Duplicate card number, foreign key violations (account_id)</li>
     *   <li>ValidationException: Bean validation failures on Card entity</li>
     *   <li>SQLException: Database connectivity or execution issues</li>
     *   <li>All exceptions logged and re-thrown for Spring Batch retry/skip logic</li>
     * </ul>
     * 
     * @param chunk the chunk of Card entities to write (non-null, non-empty)
     * @throws DataIntegrityViolationException if database constraints are violated
     * @throws ValidationException if card data fails validation rules
     * @throws SQLException if database operation fails
     * @throws Exception for any other unexpected errors
     */
    @Override
    public void write(Chunk<? extends Card> chunk) throws Exception {
        // Validate input
        if (chunk == null || chunk.isEmpty()) {
            logger.debug("Empty chunk received, skipping write operation");
            return;
        }

        List<? extends Card> cards = chunk.getItems();
        int recordCount = cards.size();

        try {
            logger.debug("Writing {} card records to database", recordCount);

            // Batch persist all card entities
            // This replaces COBOL: WRITE CARDFILE-FILE FROM CARD-RECORD
            cardRepository.saveAll(cards);

            // Flush pending changes to database
            // Ensures all SQL statements are executed within transaction
            entityManager.flush();

            // Clear persistence context to release memory
            // Prevents OutOfMemoryError with large batch processing
            // Detaches managed entities from persistence context
            entityManager.clear();

            logger.info("Successfully wrote {} card records to database", recordCount);

        } catch (DataIntegrityViolationException e) {
            // Handle constraint violations: duplicate card number, foreign key violations, etc.
            logger.error("Data integrity violation writing {} card records: {}", 
                        recordCount, e.getMessage(), e);
            throw new DataIntegrityViolationException(
                "Failed to write card records due to constraint violation: " + e.getMessage(), e);

        } catch (ValidationException e) {
            // Handle bean validation failures
            logger.error("Validation error writing {} card records: {}", 
                        recordCount, e.getMessage(), e);
            throw new ValidationException(
                "Failed to write card records due to validation error: " + e.getMessage(), e);

        } catch (Exception e) {
            // Handle any unexpected errors (SQLException, etc.)
            logger.error("Unexpected error writing {} card records: {}", 
                        recordCount, e.getMessage(), e);
            
            // Check if underlying cause is SQLException
            if (e.getCause() instanceof SQLException) {
                SQLException sqlException = (SQLException) e.getCause();
                logger.error("SQL error code: {}, SQL state: {}", 
                            sqlException.getErrorCode(), sqlException.getSQLState());
            }
            
            throw new Exception(
                "Failed to write card records: " + e.getMessage(), e);
        }
    }
}
