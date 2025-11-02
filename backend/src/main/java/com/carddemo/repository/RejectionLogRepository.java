/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.RejectionLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for RejectionLogEntry entity providing
 * CRUD operations and queries for rejected transaction analysis.
 * 
 * <p>This repository replaces DALYREJS-FILE sequential write operations
 * from COBOL program CBTRN02C.cbl with relational database access patterns.
 * 
 * <p><b>COBOL Replacement:</b> Replaces WRITE FD-REJS-RECORD operations
 * in paragraph 2500-WRITE-REJECT-REC (lines 446-465).
 * 
 * <p><b>Key Methods:</b>
 * <ul>
 *   <li>save/saveAll: Write rejected transactions (replaces WRITE FD-REJS-RECORD)</li>
 *   <li>findByTransactionId: Look up rejection by transaction ID</li>
 *   <li>findByValidationFailureReasonCode: Query rejections by reason code</li>
 *   <li>countByProcessingTimestampBetween: Count rejections in date range for reporting</li>
 * </ul>
 * 
 * @see RejectionLogEntry
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface RejectionLogRepository extends JpaRepository<RejectionLogEntry, Long> {

    /**
     * Finds a rejection log entry by transaction ID.
     * 
     * <p>Used to check if a transaction has been rejected previously.
     * 
     * @param transactionId the unique 16-character transaction identifier
     * @return Optional containing the rejection log entry if found, empty otherwise
     */
    Optional<RejectionLogEntry> findByTransactionId(String transactionId);

    /**
     * Finds all rejection log entries for a specific validation failure reason code.
     * 
     * <p>Used for analyzing specific types of validation failures:
     * <ul>
     *   <li>100 - Card not found</li>
     *   <li>101 - Account not found</li>
     *   <li>102 - Overlimit transaction</li>
     *   <li>103 - Transaction after expiration</li>
     *   <li>104 - Invalid amount</li>
     *   <li>105 - Future-dated transaction</li>
     *   <li>106 - Duplicate transaction</li>
     *   <li>107 - Invalid merchant</li>
     *   <li>109 - Account not found during update</li>
     * </ul>
     * 
     * @param reasonCode the 4-digit validation failure reason code
     * @return List of rejection log entries with the specified reason code
     */
    List<RejectionLogEntry> findByValidationFailureReasonCode(Integer reasonCode);

    /**
     * Finds rejection log entries within a processing timestamp range.
     * 
     * <p>Used for daily/monthly rejection reports and analysis.
     * 
     * @param startTimestamp the start of the time range (inclusive)
     * @param endTimestamp the end of the time range (inclusive)
     * @return List of rejection log entries within the specified time range
     */
    List<RejectionLogEntry> findByProcessingTimestampBetween(
            LocalDateTime startTimestamp,
            LocalDateTime endTimestamp
    );

    /**
     * Counts the number of rejections within a processing timestamp range.
     * 
     * <p>Used for rejection metrics and reporting without loading full entities.
     * 
     * @param startTimestamp the start of the time range (inclusive)
     * @param endTimestamp the end of the time range (inclusive)
     * @return count of rejection log entries in the specified time range
     */
    @Query("SELECT COUNT(r) FROM RejectionLogEntry r " +
           "WHERE r.processingTimestamp BETWEEN :startTimestamp AND :endTimestamp")
    long countByProcessingTimestampBetween(
            @Param("startTimestamp") LocalDateTime startTimestamp,
            @Param("endTimestamp") LocalDateTime endTimestamp
    );

    /**
     * Counts rejections by reason code within a time range.
     * 
     * <p>Used for detailed rejection analysis and reporting.
     * Returns grouped counts by reason code for the specified period.
     * 
     * @param startTimestamp the start of the time range (inclusive)
     * @param endTimestamp the end of the time range (inclusive)
     * @return List of Object arrays where [0] = reasonCode, [1] = count
     */
    @Query("SELECT r.validationFailureReasonCode, COUNT(r) " +
           "FROM RejectionLogEntry r " +
           "WHERE r.processingTimestamp BETWEEN :startTimestamp AND :endTimestamp " +
           "GROUP BY r.validationFailureReasonCode " +
           "ORDER BY COUNT(r) DESC")
    List<Object[]> countByReasonCodeBetween(
            @Param("startTimestamp") LocalDateTime startTimestamp,
            @Param("endTimestamp") LocalDateTime endTimestamp
    );

    /**
     * Finds rejection log entries for a specific card number.
     * 
     * <p>Used to analyze card-specific rejection patterns.
     * 
     * @param cardNumber the 16-digit card number
     * @return List of rejection log entries for the specified card
     */
    List<RejectionLogEntry> findByCardNumber(String cardNumber);

    /**
     * Checks if a rejection exists for a given transaction ID.
     * 
     * <p>Used for duplicate rejection detection during batch processing.
     * 
     * @param transactionId the unique transaction identifier
     * @return true if a rejection log entry exists, false otherwise
     */
    boolean existsByTransactionId(String transactionId);
}
