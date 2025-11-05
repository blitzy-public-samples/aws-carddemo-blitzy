/*
 * RejectionWriter.java
 *
 * Spring Batch ItemWriter implementation for writing rejected daily transactions
 * to rejection log table with validation error details.
 *
 * Replaces DALYREJS-FILE sequential write operations from CBTRN02C.cbl 
 * lines 446-465 (2500-WRITE-REJECT-REC paragraph).
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.carddemo.batch.writer;

import com.carddemo.entity.RejectionLogEntry;
import com.carddemo.repository.RejectionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch ItemWriter for writing rejected transaction records to the rejection log.
 * 
 * <p>This writer persists transactions that failed validation during daily transaction
 * processing, preserving the original transaction data along with detailed validation
 * failure information.
 * 
 * <p><b>COBOL Equivalent:</b> Replaces WRITE FD-REJS-RECORD FROM REJECT-RECORD 
 * in paragraph 2500-WRITE-REJECT-REC (CBTRN02C.cbl lines 446-465)
 * 
 * <p><b>Rejection Reason Codes (from CBTRN02C.cbl validation logic):</b>
 * <ul>
 *   <li>100 - INVALID CARD NUMBER FOUND (card not found in XREF file)</li>
 *   <li>101 - ACCOUNT RECORD NOT FOUND</li>
 *   <li>102 - OVERLIMIT TRANSACTION (credit limit exceeded)</li>
 *   <li>103 - TRANSACTION RECEIVED AFTER ACCT EXPIRATION</li>
 *   <li>104 - INVALID TRANSACTION AMOUNT</li>
 *   <li>105 - FUTURE-DATED TRANSACTION</li>
 *   <li>106 - DUPLICATE TRANSACTION ID</li>
 *   <li>107 - INVALID MERCHANT</li>
 *   <li>109 - ACCOUNT RECORD NOT FOUND (during update phase)</li>
 * </ul>
 * 
 * <p><b>Data Structure Preservation:</b>
 * <ul>
 *   <li>Original daily transaction record (all 350 bytes preserved)</li>
 *   <li>Validation failure reason code (4 digits matching COBOL)</li>
 *   <li>Validation failure description (76 characters matching COBOL)</li>
 *   <li>Processing timestamp for audit trail</li>
 * </ul>
 * 
 * @see org.springframework.batch.item.ItemWriter
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
@Component("rejectionWriter")
public class RejectionWriter implements ItemWriter<RejectedTransaction> {

    private static final Logger logger = LoggerFactory.getLogger(RejectionWriter.class);

    /**
     * Rejection log repository for database persistence.
     * Handles CRUD operations for rejected transaction records.
     */
    private final RejectionLogRepository rejectionLogRepository;

    /**
     * Constructs a new RejectionWriter with required dependencies.
     * 
     * @param rejectionLogRepository the repository for persisting rejection log entries
     * @throws IllegalArgumentException if rejectionLogRepository is null
     */
    public RejectionWriter(RejectionLogRepository rejectionLogRepository) {
        if (rejectionLogRepository == null) {
            throw new IllegalArgumentException("RejectionLogRepository cannot be null");
        }
        this.rejectionLogRepository = rejectionLogRepository;
        logger.info("RejectionWriter initialized successfully");
    }

    /**
     * Writes a chunk of rejected transactions to the rejection log table.
     * 
     * <p>This method persists rejected transactions with complete validation error details,
     * maintaining an audit trail of all validation failures. Each rejected transaction
     * includes the original transaction data, failure reason code, and descriptive error
     * message.
     * 
     * <p><b>COBOL Replacement Logic:</b>
     * <pre>
     * 2500-WRITE-REJECT-REC.
     *     MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
     *     MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
     *     WRITE FD-REJS-RECORD FROM REJECT-RECORD
     * </pre>
     * 
     * <p><b>Error Handling:</b> Any database write failures are logged and re-thrown
     * to trigger Spring Batch's error handling mechanisms. This ensures transaction
     * integrity and allows for retry/skip policies to be applied.
     * 
     * @param chunk the chunk of rejected transactions to write (never null)
     * @throws IllegalArgumentException if chunk is null or contains null items
     * @throws RejectionWriteException if database write operation fails
     */
    @Override
    @Transactional
    public void write(Chunk<? extends RejectedTransaction> chunk) throws Exception {
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }

        List<? extends RejectedTransaction> rejectedTransactions = chunk.getItems();
        
        if (rejectedTransactions == null || rejectedTransactions.isEmpty()) {
            logger.debug("No rejected transactions to write in this chunk");
            return;
        }

        logger.info("Writing {} rejected transaction(s) to rejection log", rejectedTransactions.size());

        List<RejectionLogEntry> rejectionLogEntries = new ArrayList<>();
        int processedCount = 0;

        try {
            // Convert each RejectedTransaction to RejectionLogEntry for persistence
            for (RejectedTransaction rejectedTransaction : rejectedTransactions) {
                if (rejectedTransaction == null) {
                    logger.warn("Skipping null rejected transaction in chunk");
                    continue;
                }

                RejectionLogEntry logEntry = createRejectionLogEntry(rejectedTransaction);
                rejectionLogEntries.add(logEntry);
                processedCount++;

                // Log individual rejection for audit trail
                if (logger.isDebugEnabled()) {
                    logger.debug("Prepared rejection log entry - Transaction ID: {}, Reason Code: {}, Description: {}",
                            rejectedTransaction.getTransactionId(),
                            rejectedTransaction.getValidationFailureReasonCode(),
                            rejectedTransaction.getValidationFailureDescription());
                }
            }

            // Batch write all rejection log entries
            if (!rejectionLogEntries.isEmpty()) {
                List<RejectionLogEntry> savedEntries = rejectionLogRepository.saveAll(rejectionLogEntries);
                logger.info("Successfully wrote {} rejection log entries to database", savedEntries.size());
            }

        } catch (Exception e) {
            logger.error("Error writing rejected transactions to rejection log. Processed {}/{} transactions before failure",
                    processedCount, rejectedTransactions.size(), e);
            throw new RejectionWriteException(
                    "Failed to write rejected transactions to rejection log: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a RejectionLogEntry entity from a RejectedTransaction.
     * 
     * <p>This method maps all fields from the rejected transaction to the database
     * entity, preserving the complete 80-byte validation trailer format from COBOL:
     * <ul>
     *   <li>4-byte reason code (WS-VALIDATION-FAIL-REASON)</li>
     *   <li>76-byte reason description (WS-VALIDATION-FAIL-REASON-DESC)</li>
     * </ul>
     * 
     * @param rejectedTransaction the rejected transaction to convert
     * @return a fully populated RejectionLogEntry ready for persistence
     * @throws IllegalArgumentException if rejectedTransaction is null or missing required fields
     */
    private RejectionLogEntry createRejectionLogEntry(RejectedTransaction rejectedTransaction) {
        if (rejectedTransaction == null) {
            throw new IllegalArgumentException("RejectedTransaction cannot be null");
        }

        // Validate required fields
        if (rejectedTransaction.getTransactionId() == null || rejectedTransaction.getTransactionId().trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID is required for rejection log entry");
        }
        if (rejectedTransaction.getValidationFailureReasonCode() == null) {
            throw new IllegalArgumentException("Validation failure reason code is required");
        }

        RejectionLogEntry logEntry = new RejectionLogEntry();

        // Set primary identification
        logEntry.setTransactionId(rejectedTransaction.getTransactionId());
        logEntry.setProcessingTimestamp(LocalDateTime.now());

        // Set validation failure details (80-byte COBOL trailer equivalent)
        logEntry.setValidationFailureReasonCode(rejectedTransaction.getValidationFailureReasonCode());
        logEntry.setValidationFailureDescription(
                rejectedTransaction.getValidationFailureDescription() != null 
                    ? rejectedTransaction.getValidationFailureDescription() 
                    : "");

        // Preserve complete original transaction data (350-byte DALYTRAN-RECORD)
        logEntry.setOriginalTransactionData(rejectedTransaction.getOriginalTransactionData());
        
        // Set individual transaction fields for queryability
        logEntry.setCardNumber(rejectedTransaction.getCardNumber());
        logEntry.setTransactionAmount(rejectedTransaction.getTransactionAmount());
        logEntry.setTransactionTypeCode(rejectedTransaction.getTransactionTypeCode());
        logEntry.setTransactionCategoryCode(rejectedTransaction.getTransactionCategoryCode());
        logEntry.setMerchantId(rejectedTransaction.getMerchantId());
        logEntry.setMerchantName(rejectedTransaction.getMerchantName());
        logEntry.setOriginalTimestamp(rejectedTransaction.getOriginalTimestamp());

        return logEntry;
    }

    /**
     * Custom exception for rejection write failures.
     * 
     * <p>This exception is thrown when the writer cannot persist rejected transactions
     * to the database, allowing Spring Batch to handle the failure appropriately
     * through configured retry/skip policies.
     */
    public static class RejectionWriteException extends RuntimeException {
        
        private static final long serialVersionUID = 1L;

        /**
         * Constructs a new RejectionWriteException with the specified detail message and cause.
         * 
         * @param message the detail message
         * @param cause the cause of the exception
         */
        public RejectionWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
