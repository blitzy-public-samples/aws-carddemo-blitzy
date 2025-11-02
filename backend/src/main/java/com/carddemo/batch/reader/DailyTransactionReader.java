/*
 * DailyTransactionReader.java
 *
 * Spring Batch ItemReader implementation for reading daily transaction records
 * from the staging table. This reader replaces the sequential DALYTRAN-FILE
 * read operations from COBOL program CBTRN02C.cbl.
 *
 * Original COBOL:
 *   - File: DALYTRAN-FILE (lines 29-32 in CBTRN02C.cbl)
 *   - Read operation: 1000-DALYTRAN-GET-NEXT (lines 345-369)
 *   - Sequential access with END-OF-FILE detection
 *
 * Migration Notes:
 *   - COBOL sequential file → PostgreSQL staging table with cursor-based pagination
 *   - OPEN INPUT → No explicit open (managed by Spring Batch)
 *   - READ DALYTRAN-FILE → read() method with pagination
 *   - File Status '10' (EOF) → return null to signal end of input
 *   - Record structure preserved from CVTRA06Y copybook
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 */
package com.carddemo.batch.reader;

import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Batch ItemReader for reading daily transaction records from the staging table.
 * 
 * This reader implements cursor-based pagination to efficiently process large volumes
 * of transaction data without loading the entire dataset into memory. It reads only
 * PENDING status records for validation and posting to the permanent transaction table.
 * 
 * <p>Replaces COBOL sequential file operations:</p>
 * <pre>
 * COBOL:                          Java Equivalent:
 * ------                          ----------------
 * OPEN INPUT DALYTRAN-FILE        @PostConstruct initialization
 * READ DALYTRAN-FILE              read() method call
 * AT END MOVE 'Y' TO END-OF-FILE  return null
 * </pre>
 * 
 * <p>Configuration:</p>
 * <ul>
 *   <li>Chunk size: Controlled by Spring Batch job configuration (default 1000)</li>
 *   <li>Fetch size: Configurable via batch.reader.fetch-size property</li>
 *   <li>Thread-safety: NOT thread-safe (designed for single-threaded batch processing)</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component
public class DailyTransactionReader implements ItemReader<DailyTransactionReader.DailyTransaction> {

    private static final Logger logger = LoggerFactory.getLogger(DailyTransactionReader.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Fetch size for pagination - number of records to retrieve per database query.
     * Configurable via application properties: batch.reader.fetch-size
     * Default: 1000 records per fetch
     */
    @Value("${batch.reader.fetch-size:1000}")
    private int fetchSize;

    /**
     * Current cursor position in the result set.
     * Tracks the index of the next record to be read.
     */
    private int currentPosition = 0;

    /**
     * In-memory buffer holding the current batch of records.
     * Populated by fetching records in chunks from the database.
     */
    private List<Object[]> currentBatch;

    /**
     * Index within the current batch buffer.
     * Points to the next record to be returned from the current batch.
     */
    private int batchIndex = 0;

    /**
     * Flag indicating whether all records have been read.
     * Set to true when the database query returns fewer records than fetch size.
     */
    private boolean exhausted = false;

    /**
     * Initializes the reader after dependency injection.
     * Validates configuration and logs reader initialization.
     */
    @PostConstruct
    public void init() {
        logger.info("Initializing DailyTransactionReader with fetch size: {}", fetchSize);
        
        if (fetchSize <= 0) {
            throw new IllegalArgumentException(
                "Fetch size must be positive. Configured value: " + fetchSize);
        }
        
        if (entityManager == null) {
            throw new IllegalStateException(
                "EntityManager not injected. Check JPA configuration.");
        }
    }

    /**
     * Reads the next daily transaction record from the staging table.
     * 
     * This method implements the core ItemReader contract for Spring Batch.
     * It uses cursor-based pagination to fetch records in batches, maintaining
     * position state between calls.
     * 
     * <p>Processing Flow:</p>
     * <ol>
     *   <li>Check if current batch is exhausted → fetch next batch if needed</li>
     *   <li>Return null if no more records (signals end of input to Spring Batch)</li>
     *   <li>Convert database row to DailyTransaction entity</li>
     *   <li>Increment position counters</li>
     *   <li>Return the transaction record</li>
     * </ol>
     * 
     * <p>COBOL Equivalent:</p>
     * <pre>
     * 1000-DALYTRAN-GET-NEXT.
     *     READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
     *     IF DALYTRAN-STATUS = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         IF DALYTRAN-STATUS = '10'
     *             MOVE 16 TO APPL-RESULT
     *             MOVE 'Y' TO END-OF-FILE
     * </pre>
     * 
     * @return DailyTransaction record, or null if no more records available
     * @throws Exception if database access fails or data conversion errors occur
     */
    @Override
    public DailyTransaction read() throws Exception {
        // Check if we need to fetch the next batch
        if (currentBatch == null || batchIndex >= currentBatch.size()) {
            if (exhausted) {
                // All records processed - equivalent to COBOL END-OF-FILE
                logger.info("End of daily transaction file reached. Total records read: {}", 
                           currentPosition);
                return null;
            }
            
            // Fetch next batch of records
            fetchNextBatch();
            
            // Check if batch is empty (no more records)
            if (currentBatch == null || currentBatch.isEmpty()) {
                exhausted = true;
                logger.info("No more records found. Total records read: {}", currentPosition);
                return null;
            }
            
            // Reset batch index for new batch
            batchIndex = 0;
        }

        // Get current record from batch
        Object[] row = currentBatch.get(batchIndex);
        batchIndex++;
        currentPosition++;

        // Convert database row to DailyTransaction entity
        DailyTransaction transaction = convertRowToTransaction(row);
        
        if (logger.isDebugEnabled()) {
            logger.debug("Read transaction ID: {} at position: {}", 
                        transaction.getTransactionId(), currentPosition);
        }

        return transaction;
    }

    /**
     * Fetches the next batch of records from the database.
     * 
     * This method executes a paginated query against the daily_transaction staging
     * table, retrieving only PENDING status records ordered by transaction_id.
     * 
     * <p>SQL Query Strategy:</p>
     * <pre>
     * SELECT columns
     * FROM daily_transaction
     * WHERE status = 'PENDING'
     * ORDER BY transaction_id
     * LIMIT fetchSize OFFSET currentPosition
     * </pre>
     * 
     * <p>Performance Considerations:</p>
     * <ul>
     *   <li>Uses database-level pagination (LIMIT/OFFSET) for memory efficiency</li>
     *   <li>Requires index on (status, transaction_id) for optimal performance</li>
     *   <li>Fetch size balances between memory usage and database round trips</li>
     * </ul>
     * 
     * @throws RuntimeException if database query fails
     */
    private void fetchNextBatch() {
        try {
            logger.debug("Fetching batch starting at position: {}", currentPosition);
            
            // Create parameterized query for PENDING transactions
            // Ordered by transaction_id for consistent, repeatable reads
            TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT " +
                "  t.transactionId, " +
                "  t.typeCode, " +
                "  t.categoryCode, " +
                "  t.source, " +
                "  t.description, " +
                "  t.amount, " +
                "  t.merchantId, " +
                "  t.merchantName, " +
                "  t.merchantCity, " +
                "  t.merchantZip, " +
                "  t.cardNumber, " +
                "  t.originalTimestamp, " +
                "  t.status " +
                "FROM DailyTransactionStaging t " +
                "WHERE t.status = :status " +
                "ORDER BY t.transactionId",
                Object[].class
            );
            
            query.setParameter("status", "PENDING");
            query.setFirstResult(currentPosition);
            query.setMaxResults(fetchSize);
            
            // Execute query and store results
            currentBatch = query.getResultList();
            
            logger.debug("Fetched {} records in current batch", 
                        currentBatch != null ? currentBatch.size() : 0);
            
            // Check if we've reached the end
            if (currentBatch != null && currentBatch.size() < fetchSize) {
                // Fetched fewer records than requested - this is the last batch
                exhausted = true;
                logger.debug("Last batch detected (fetched {} < {})", 
                           currentBatch.size(), fetchSize);
            }
            
        } catch (Exception e) {
            logger.error("Error fetching batch at position {}: {}", 
                        currentPosition, e.getMessage(), e);
            throw new RuntimeException(
                "Failed to fetch daily transaction batch at position " + currentPosition, e);
        }
    }

    /**
     * Converts a database result row array to a DailyTransaction entity.
     * 
     * This method maps the raw database columns to a strongly-typed entity object,
     * performing necessary type conversions and null handling.
     * 
     * <p>Field Mapping (corresponds to COBOL DALYTRAN-RECORD from CVTRA06Y):</p>
     * <pre>
     * COBOL Field             → Java Field           → DB Column
     * ---------------         -----------------      ----------------
     * DALYTRAN-ID             → transactionId        → transaction_id
     * DALYTRAN-TYPE-CD        → typeCode             → type_code
     * DALYTRAN-CAT-CD         → categoryCode         → category_code
     * DALYTRAN-SOURCE         → source               → source
     * DALYTRAN-DESC           → description          → description
     * DALYTRAN-AMT            → amount               → amount
     * DALYTRAN-MERCHANT-ID    → merchantId           → merchant_id
     * DALYTRAN-MERCHANT-NAME  → merchantName         → merchant_name
     * DALYTRAN-MERCHANT-CITY  → merchantCity         → merchant_city
     * DALYTRAN-MERCHANT-ZIP   → merchantZip          → merchant_zip
     * DALYTRAN-CARD-NUM       → cardNumber           → card_number
     * DALYTRAN-ORIG-TS        → originalTimestamp    → original_timestamp
     * </pre>
     * 
     * @param row Array of column values from database query result
     * @return DailyTransaction entity populated with row data
     * @throws IllegalArgumentException if row data is invalid or incomplete
     */
    private DailyTransaction convertRowToTransaction(Object[] row) {
        try {
            if (row == null || row.length < 13) {
                throw new IllegalArgumentException(
                    "Invalid row data: expected 13 columns, got " + 
                    (row == null ? "null" : row.length));
            }

            DailyTransaction transaction = new DailyTransaction();
            
            // Map columns to entity fields with null safety
            transaction.setTransactionId((String) row[0]);
            transaction.setTypeCode((String) row[1]);
            transaction.setCategoryCode((Integer) row[2]);
            transaction.setSource((String) row[3]);
            transaction.setDescription((String) row[4]);
            transaction.setAmount((BigDecimal) row[5]);
            transaction.setMerchantId((String) row[6]);
            transaction.setMerchantName((String) row[7]);
            transaction.setMerchantCity((String) row[8]);
            transaction.setMerchantZip((String) row[9]);
            transaction.setCardNumber((String) row[10]);
            transaction.setOriginalTimestamp((LocalDateTime) row[11]);
            transaction.setStatus((String) row[12]);
            
            return transaction;
            
        } catch (ClassCastException e) {
            logger.error("Type conversion error in row data: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Invalid data type in row", e);
        } catch (Exception e) {
            logger.error("Error converting row to transaction: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to convert database row to transaction", e);
        }
    }

    /**
     * Resets the reader state to initial conditions.
     * 
     * This method is called by Spring Batch when job restart/recovery is needed.
     * It clears all cursor state and cached data, allowing the reader to start
     * fresh from the beginning.
     * 
     * <p>COBOL Equivalent:</p>
     * <pre>
     * CLOSE DALYTRAN-FILE
     * OPEN INPUT DALYTRAN-FILE
     * MOVE 'N' TO END-OF-FILE
     * </pre>
     * 
     * Note: In a production implementation with job restart support, this method
     * should accept an ExecutionContext parameter to restore position from a
     * previously failed job execution.
     */
    public void reset() {
        logger.info("Resetting DailyTransactionReader to initial state");
        currentPosition = 0;
        currentBatch = null;
        batchIndex = 0;
        exhausted = false;
    }

    /**
     * Daily Transaction Entity
     * 
     * Represents a single daily transaction record from the staging table.
     * This entity corresponds to the COBOL DALYTRAN-RECORD structure defined
     * in copybook CVTRA06Y.
     * 
     * <p>Data Structure Mapping:</p>
     * <pre>
     * COBOL (CVTRA06Y):                      Java Entity:
     * -----------------                      ------------
     * 01 DALYTRAN-RECORD.                    class DailyTransaction
     *    05 DALYTRAN-ID          PIC X(16)   String transactionId (16 chars)
     *    05 DALYTRAN-TYPE-CD     PIC X(02)   String typeCode (2 chars)
     *    05 DALYTRAN-CAT-CD      PIC 9(04)   Integer categoryCode
     *    05 DALYTRAN-SOURCE      PIC X(10)   String source (10 chars)
     *    05 DALYTRAN-DESC        PIC X(100)  String description (100 chars)
     *    05 DALYTRAN-AMT         PIC S9(09)V99 COMP-3  BigDecimal amount (11,2)
     *    05 DALYTRAN-MERCHANT-ID PIC X(15)   String merchantId (15 chars)
     *    05 DALYTRAN-MERCHANT-NAME PIC X(50) String merchantName (50 chars)
     *    05 DALYTRAN-MERCHANT-CITY PIC X(50) String merchantCity (50 chars)
     *    05 DALYTRAN-MERCHANT-ZIP PIC X(10)  String merchantZip (10 chars)
     *    05 DALYTRAN-CARD-NUM    PIC X(16)   String cardNumber (16 chars)
     *    05 DALYTRAN-ORIG-TS     PIC X(26)   LocalDateTime originalTimestamp
     * </pre>
     * 
     * Note: This is a minimal entity definition for use within the ItemReader.
     * The full DailyTransactionStaging JPA entity may include additional fields,
     * annotations, and relationships managed by the entity framework.
     */
    public static class DailyTransaction {
        
        /** Transaction unique identifier (DALYTRAN-ID) */
        private String transactionId;
        
        /** Transaction type code (DALYTRAN-TYPE-CD): 'DR' = Debit, 'CR' = Credit */
        private String typeCode;
        
        /** Transaction category code (DALYTRAN-CAT-CD): 1-9999 */
        private Integer categoryCode;
        
        /** Transaction source (DALYTRAN-SOURCE): 'POS', 'ATM', 'ONLINE', etc. */
        private String source;
        
        /** Transaction description (DALYTRAN-DESC) */
        private String description;
        
        /** 
         * Transaction amount (DALYTRAN-AMT)
         * Precision: 11 digits total, 2 decimal places
         * COBOL COMP-3 S9(09)V99 → BigDecimal(11,2)
         * Positive = debit to customer (charge)
         * Negative = credit to customer (payment/refund)
         */
        private BigDecimal amount;
        
        /** Merchant identifier (DALYTRAN-MERCHANT-ID) */
        private String merchantId;
        
        /** Merchant name (DALYTRAN-MERCHANT-NAME) */
        private String merchantName;
        
        /** Merchant city (DALYTRAN-MERCHANT-CITY) */
        private String merchantCity;
        
        /** Merchant ZIP code (DALYTRAN-MERCHANT-ZIP) */
        private String merchantZip;
        
        /** Card number used for transaction (DALYTRAN-CARD-NUM) */
        private String cardNumber;
        
        /** Original transaction timestamp (DALYTRAN-ORIG-TS) */
        private LocalDateTime originalTimestamp;
        
        /** Processing status: PENDING, POSTED, REJECTED */
        private String status;

        // Getters and Setters

        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        public String getTypeCode() {
            return typeCode;
        }

        public void setTypeCode(String typeCode) {
            this.typeCode = typeCode;
        }

        public Integer getCategoryCode() {
            return categoryCode;
        }

        public void setCategoryCode(Integer categoryCode) {
            this.categoryCode = categoryCode;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getMerchantId() {
            return merchantId;
        }

        public void setMerchantId(String merchantId) {
            this.merchantId = merchantId;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public void setMerchantName(String merchantName) {
            this.merchantName = merchantName;
        }

        public String getMerchantCity() {
            return merchantCity;
        }

        public void setMerchantCity(String merchantCity) {
            this.merchantCity = merchantCity;
        }

        public String getMerchantZip() {
            return merchantZip;
        }

        public void setMerchantZip(String merchantZip) {
            this.merchantZip = merchantZip;
        }

        public String getCardNumber() {
            return cardNumber;
        }

        public void setCardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
        }

        public LocalDateTime getOriginalTimestamp() {
            return originalTimestamp;
        }

        public void setOriginalTimestamp(LocalDateTime originalTimestamp) {
            this.originalTimestamp = originalTimestamp;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        @Override
        public String toString() {
            return "DailyTransaction{" +
                   "transactionId='" + transactionId + '\'' +
                   ", typeCode='" + typeCode + '\'' +
                   ", categoryCode=" + categoryCode +
                   ", amount=" + amount +
                   ", cardNumber='" + cardNumber + '\'' +
                   ", status='" + status + '\'' +
                   '}';
        }
    }
}
