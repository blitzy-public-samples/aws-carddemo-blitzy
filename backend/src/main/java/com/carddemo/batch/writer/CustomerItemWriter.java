/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.writer;

import com.carddemo.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.ValidationException;
import java.sql.SQLException;
import java.util.List;

/**
 * Spring Batch ItemWriter implementation for persisting Customer entities to PostgreSQL database.
 * 
 * <p>This class replaces COBOL WRITE operations to CUSTFILE VSAM in the CBCUS01C batch program.
 * It implements chunk-oriented processing with batch insert/update operations using Spring Data JPA.
 * 
 * <p>Key Features:
 * <ul>
 *   <li>Batch persistence using JPA repository saveAll() method</li>
 *   <li>Transaction management with READ_COMMITTED isolation level</li>
 *   <li>EntityManager flush/clear for memory optimization</li>
 *   <li>Comprehensive error handling for data integrity violations</li>
 *   <li>Operational monitoring via SLF4J logging</li>
 * </ul>
 * 
 * <p>Transaction Configuration:
 * <ul>
 *   <li>Isolation: READ_COMMITTED (per Section 0.3 requirements)</li>
 *   <li>Chunk size: 1000 records per transaction (per Section 0.5)</li>
 *   <li>Automatic rollback on exception</li>
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
 * @see Customer
 * @see CustomerRepository
 * @see org.springframework.batch.item.ItemWriter
 * @since 1.0
 */
@Component
public class CustomerItemWriter implements ItemWriter<Customer> {

    private static final Logger logger = LoggerFactory.getLogger(CustomerItemWriter.class);

    private final CustomerRepository customerRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Constructs a new CustomerItemWriter with required dependencies.
     * 
     * @param customerRepository the JPA repository for Customer entity persistence
     */
    public CustomerItemWriter(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * Writes a chunk of Customer entities to the PostgreSQL database.
     * 
     * <p>This method replaces COBOL WRITE operations to CUSTFILE VSAM. It persists
     * a batch of customer records using JPA saveAll() for optimal performance.
     * 
     * <p>Processing Flow:
     * <ol>
     *   <li>Validate input chunk is not null or empty</li>
     *   <li>Persist all customers using repository saveAll()</li>
     *   <li>Flush EntityManager to commit pending changes</li>
     *   <li>Clear EntityManager to release memory</li>
     *   <li>Log batch metrics for monitoring</li>
     * </ol>
     * 
     * <p>Transaction Semantics:
     * <ul>
     *   <li>Entire chunk commits or rolls back as atomic unit</li>
     *   <li>READ_COMMITTED isolation prevents dirty reads</li>
     *   <li>Automatic rollback on any exception</li>
     * </ul>
     * 
     * <p>Error Handling:
     * <ul>
     *   <li>DataIntegrityViolationException: Duplicate customer ID or constraint violations</li>
     *   <li>ValidationException: Bean validation failures on Customer entity</li>
     *   <li>SQLException: Database connectivity or execution issues</li>
     *   <li>All exceptions logged and re-thrown for Spring Batch retry/skip logic</li>
     * </ul>
     * 
     * @param chunk the chunk of Customer entities to write (non-null, non-empty)
     * @throws DataIntegrityViolationException if database constraints are violated
     * @throws ValidationException if customer data fails validation rules
     * @throws SQLException if database operation fails
     * @throws Exception for any other unexpected errors
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void write(Chunk<? extends Customer> chunk) throws Exception {
        // Validate input
        if (chunk == null || chunk.isEmpty()) {
            logger.debug("Empty chunk received, skipping write operation");
            return;
        }

        List<? extends Customer> customers = chunk.getItems();
        int recordCount = customers.size();

        try {
            logger.debug("Writing {} customer records to database", recordCount);

            // Batch persist all customer entities
            // This replaces COBOL: WRITE CUSTFILE-FILE FROM CUSTOMER-RECORD
            customerRepository.saveAll(customers);

            // Flush pending changes to database
            // Ensures all SQL statements are executed within transaction
            entityManager.flush();

            // Clear persistence context to release memory
            // Prevents OutOfMemoryError with large batch processing
            // Detaches managed entities from persistence context
            entityManager.clear();

            logger.info("Successfully wrote {} customer records to database", recordCount);

        } catch (DataIntegrityViolationException e) {
            // Handle constraint violations: duplicate customer ID, foreign key violations, etc.
            logger.error("Data integrity violation writing {} customer records: {}", 
                        recordCount, e.getMessage(), e);
            throw new DataIntegrityViolationException(
                "Failed to write customer records due to constraint violation: " + e.getMessage(), e);

        } catch (ValidationException e) {
            // Handle bean validation failures
            logger.error("Validation error writing {} customer records: {}", 
                        recordCount, e.getMessage(), e);
            throw new ValidationException(
                "Failed to write customer records due to validation error: " + e.getMessage(), e);

        } catch (Exception e) {
            // Handle any unexpected errors (SQLException, etc.)
            logger.error("Unexpected error writing {} customer records: {}", 
                        recordCount, e.getMessage(), e);
            
            // Check if underlying cause is SQLException
            if (e.getCause() instanceof SQLException) {
                SQLException sqlException = (SQLException) e.getCause();
                logger.error("SQL error code: {}, SQL state: {}", 
                            sqlException.getErrorCode(), sqlException.getSQLState());
            }
            
            throw new Exception(
                "Failed to write customer records: " + e.getMessage(), e);
        }
    }
}
