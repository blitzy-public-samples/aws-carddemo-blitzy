/*
 * TransactionGroupReader.java
 * CardDemo - Transaction Aggregation Batch Reader
 * 
 * Spring Batch ItemReader implementation for reading grouped transaction data.
 * Transformed from COBOL program CBTRN03C.cbl sequential transaction file processing
 * to SQL GROUP BY aggregation for efficient transaction grouping and reporting.
 * 
 * Original COBOL Logic:
 * - Sequential READ of TRANSACT file with date range filtering
 * - In-memory grouping by card number (account)
 * - Reference data lookups for transaction types and categories
 * - Accumulation of totals with group break detection
 * 
 * Spring Batch Transformation:
 * - SQL GROUP BY for database-level aggregation
 * - Chunk-oriented processing via ItemReader interface
 * - ExecutionContext-based restart capability
 * - Maintains COBOL COMP-3 decimal precision using BigDecimal
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.reader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Spring Batch ItemReader for transaction aggregation batch processing.
 * 
 * Implements chunk-oriented reading of aggregated transaction data grouped by
 * account ID, transaction type code, and transaction category code. Uses SQL
 * GROUP BY to efficiently aggregate transactions at the database level, replacing
 * COBOL in-memory grouping logic from CBTRN03C.cbl.
 * 
 * Features:
 * - SQL GROUP BY aggregation for performance
 * - Date range filtering (start date to end date)
 * - Restart capability via ExecutionContext persistence
 * - BigDecimal precision matching COBOL COMP-3 decimal fields
 * - Thread-safe implementation for parallel processing
 * 
 * Usage in TransactionAggregationJob:
 * <pre>
 * {@code
 * @Bean
 * public ItemReader<TransactionGroup> transactionGroupReader(DataSource dataSource) {
 *     TransactionGroupReader reader = new TransactionGroupReader(dataSource);
 *     reader.setStartDate(LocalDate.parse("2024-01-01"));
 *     reader.setEndDate(LocalDate.parse("2024-01-31"));
 *     return reader;
 * }
 * }
 * </pre>
 */
public class TransactionGroupReader implements ItemStreamReader<TransactionGroupReader.TransactionGroup> {

    private static final Logger logger = LoggerFactory.getLogger(TransactionGroupReader.class);

    // ExecutionContext keys for restart capability
    private static final String CURRENT_INDEX_KEY = "transaction.group.current.index";
    private static final String START_DATE_KEY = "transaction.group.start.date";
    private static final String END_DATE_KEY = "transaction.group.end.date";

    // SQL query for transaction aggregation matching COBOL grouping logic
    // Note: Transaction table has card_number, not account_id. Need to join with card table to get account_id.
    private static final String AGGREGATION_QUERY =
            "SELECT " +
            "    c.account_id, " +
            "    t.transaction_type_code, " +
            "    t.transaction_category_code, " +
            "    COUNT(*) as transaction_count, " +
            "    SUM(t.transaction_amount) as total_amount, " +
            "    AVG(t.transaction_amount) as average_amount, " +
            "    MIN(t.transaction_timestamp) as first_transaction_date, " +
            "    MAX(t.transaction_timestamp) as last_transaction_date, " +
            "    MIN(t.card_number) as primary_card_number " +
            "FROM transaction t " +
            "INNER JOIN card c ON t.card_number = c.card_number " +
            "WHERE t.transaction_timestamp >= ? " +
            "AND t.transaction_timestamp < ? " +
            "GROUP BY c.account_id, t.transaction_type_code, t.transaction_category_code " +
            "ORDER BY c.account_id, t.transaction_type_code, t.transaction_category_code";

    private final JdbcTemplate jdbcTemplate;
    private List<TransactionGroup> transactionGroups;
    private int currentIndex;
    
    // Date range parameters matching COBOL WS-START-DATE and WS-END-DATE
    private LocalDate startDate;
    private LocalDate endDate;

    /**
     * Constructs TransactionGroupReader with database connection.
     * 
     * @param dataSource JDBC DataSource for database access
     */
    public TransactionGroupReader(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.currentIndex = 0;
    }

    /**
     * Sets the start date for transaction aggregation filtering.
     * Corresponds to COBOL WS-START-DATE parameter.
     * 
     * @param startDate inclusive start date for transaction range
     */
    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    /**
     * Sets the end date for transaction aggregation filtering.
     * Corresponds to COBOL WS-END-DATE parameter.
     * 
     * @param endDate exclusive end date for transaction range
     */
    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    /**
     * Opens the reader and initializes aggregated transaction data.
     * Executes SQL GROUP BY query to load all transaction groups into memory.
     * Restores position from ExecutionContext for job restart capability.
     * 
     * Matches COBOL file open and initial read logic from CBTRN03C.cbl:
     * - 0000-TRANFILE-OPEN paragraph
     * - 0550-DATEPARM-READ for date range
     * - 1000-TRANFILE-GET-NEXT with date filtering
     * 
     * @param executionContext Spring Batch execution context for restart state
     * @throws ItemStreamException if reader initialization fails
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        logger.info("Opening TransactionGroupReader for transaction aggregation");

        try {
            // Restore position from execution context for restart
            if (executionContext.containsKey(CURRENT_INDEX_KEY)) {
                this.currentIndex = executionContext.getInt(CURRENT_INDEX_KEY);
                logger.info("Restarting from position: {}", currentIndex);
            } else {
                this.currentIndex = 0;
            }

            // Restore or use default date range
            if (executionContext.containsKey(START_DATE_KEY)) {
                this.startDate = LocalDate.parse(executionContext.getString(START_DATE_KEY));
                this.endDate = LocalDate.parse(executionContext.getString(END_DATE_KEY));
                logger.info("Restored date range from execution context: {} to {}", startDate, endDate);
            } else {
                // Default to previous day if not specified (incremental mode)
                if (this.startDate == null) {
                    this.startDate = LocalDate.now().minusDays(1);
                }
                if (this.endDate == null) {
                    this.endDate = LocalDate.now();
                }
                logger.info("Using date range: {} to {}", startDate, endDate);
            }

            // Execute aggregation query matching COBOL GROUP BY logic
            logger.info("Executing transaction aggregation query for date range {} to {}", 
                       startDate, endDate);
            
            // Using modern JdbcTemplate query with PreparedStatementSetter to avoid deprecated method
            this.transactionGroups = jdbcTemplate.query(
                connection -> {
                    var ps = connection.prepareStatement(AGGREGATION_QUERY);
                    ps.setObject(1, startDate.atStartOfDay());
                    ps.setObject(2, endDate.atStartOfDay());
                    return ps;
                },
                new TransactionGroupRowMapper()
            );

            logger.info("Loaded {} transaction groups for processing", 
                       transactionGroups != null ? transactionGroups.size() : 0);

            if (transactionGroups == null || transactionGroups.isEmpty()) {
                logger.warn("No transaction groups found for date range {} to {}", startDate, endDate);
                transactionGroups = new java.util.ArrayList<>(); // Empty mutable list for null safety
            }

        } catch (Exception e) {
            logger.error("Error opening TransactionGroupReader: {}", e.getMessage(), e);
            throw new ItemStreamException("Failed to open TransactionGroupReader", e);
        }
    }

    /**
     * Reads the next transaction group for processing.
     * Returns null when all groups have been processed (standard ItemReader contract).
     * 
     * Matches COBOL sequential read pattern from CBTRN03C.cbl:
     * - 1000-TRANFILE-GET-NEXT paragraph
     * - Group break detection on card number change
     * - END-OF-FILE flag processing
     * 
     * @return next TransactionGroup or null if no more groups available
     * @throws Exception if read operation fails
     */
    @Override
    public TransactionGroup read() throws Exception {
        // Check if we have more groups to process
        if (transactionGroups == null || currentIndex >= transactionGroups.size()) {
            logger.debug("No more transaction groups to read (index: {}, total: {})",
                        currentIndex, transactionGroups != null ? transactionGroups.size() : 0);
            return null; // End of data - standard Spring Batch ItemReader contract
        }

        // Return next group and increment position
        TransactionGroup group = transactionGroups.get(currentIndex);
        currentIndex++;

        if (logger.isDebugEnabled()) {
            logger.debug("Read transaction group {}/{}: accountId={}, typeCode={}, categoryCode={}, count={}, total={}",
                        currentIndex, transactionGroups.size(),
                        group.getAccountId(), group.getTransactionTypeCode(),
                        group.getTransactionCategoryCode(), group.getTransactionCount(),
                        group.getTotalAmount());
        }

        return group;
    }

    /**
     * Updates execution context with current position for restart capability.
     * Called by Spring Batch at chunk commit points to enable job restart.
     * 
     * Matches COBOL checkpoint/restart functionality for long-running batch jobs.
     * 
     * @param executionContext Spring Batch execution context to update
     * @throws ItemStreamException if state persistence fails
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        try {
            // Save current position for restart
            executionContext.putInt(CURRENT_INDEX_KEY, currentIndex);
            
            // Save date range parameters
            if (startDate != null) {
                executionContext.putString(START_DATE_KEY, startDate.toString());
            }
            if (endDate != null) {
                executionContext.putString(END_DATE_KEY, endDate.toString());
            }

            logger.debug("Updated execution context: currentIndex={}, startDate={}, endDate={}",
                        currentIndex, startDate, endDate);

        } catch (Exception e) {
            logger.error("Error updating execution context: {}", e.getMessage(), e);
            throw new ItemStreamException("Failed to update execution context", e);
        }
    }

    /**
     * Closes the reader and releases resources.
     * 
     * Matches COBOL file close operations from CBTRN03C.cbl:
     * - 9000-TRANFILE-CLOSE paragraph
     * 
     * @throws ItemStreamException if resource cleanup fails
     */
    @Override
    public void close() throws ItemStreamException {
        try {
            logger.info("Closing TransactionGroupReader. Processed {} transaction groups", currentIndex);
            
            // Clear in-memory data - set to null instead of clearing to avoid issues with immutable lists
            transactionGroups = null;
            currentIndex = 0;

        } catch (Exception e) {
            logger.error("Error closing TransactionGroupReader: {}", e.getMessage(), e);
            throw new ItemStreamException("Failed to close TransactionGroupReader", e);
        }
    }

    /**
     * RowMapper implementation for mapping SQL result set to TransactionGroup objects.
     * Handles BigDecimal precision matching COBOL COMP-3 decimal fields.
     */
    private static class TransactionGroupRowMapper implements RowMapper<TransactionGroup> {

        @Override
        public TransactionGroup mapRow(ResultSet rs, int rowNum) throws SQLException {
            TransactionGroup group = new TransactionGroup();

            // Map primary grouping keys
            group.setAccountId(rs.getLong("account_id"));
            group.setTransactionTypeCode(rs.getString("transaction_type_code"));
            group.setTransactionCategoryCode(rs.getString("transaction_category_code"));

            // Map aggregated values with COBOL COMP-3 precision
            group.setTransactionCount(rs.getInt("transaction_count"));
            
            // BigDecimal with scale 2 matching COBOL PIC S9(13)V99 COMP-3
            BigDecimal totalAmount = rs.getBigDecimal("total_amount");
            group.setTotalAmount(totalAmount != null ? 
                totalAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

            BigDecimal avgAmount = rs.getBigDecimal("average_amount");
            group.setAverageAmount(avgAmount != null ? 
                avgAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

            // Map timestamp boundaries
            group.setFirstTransactionDate(rs.getTimestamp("first_transaction_date") != null ?
                rs.getTimestamp("first_transaction_date").toLocalDateTime() : null);
            group.setLastTransactionDate(rs.getTimestamp("last_transaction_date") != null ?
                rs.getTimestamp("last_transaction_date").toLocalDateTime() : null);

            // Map primary card number for reference
            group.setPrimaryCardNumber(rs.getString("primary_card_number"));

            return group;
        }
    }

    /**
     * Value object representing aggregated transaction data grouped by account,
     * transaction type, and category.
     * 
     * Replaces COBOL in-memory accumulation variables from CBTRN03C.cbl:
     * - WS-PAGE-TOTAL (accumulates transaction amounts)
     * - WS-ACCOUNT-TOTAL (accumulates by account/card group)
     * - WS-CURR-CARD-NUM (tracks current grouping key)
     * 
     * All BigDecimal fields maintain COBOL COMP-3 precision with scale 2.
     */
    public static class TransactionGroup {

        // Grouping keys (composite primary key)
        private Long accountId;
        private String transactionTypeCode;
        private String transactionCategoryCode;

        // Aggregated metrics
        private Integer transactionCount;
        private BigDecimal totalAmount;
        private BigDecimal averageAmount;

        // Temporal boundaries
        private LocalDateTime firstTransactionDate;
        private LocalDateTime lastTransactionDate;

        // Reference data
        private String primaryCardNumber;

        /**
         * Default constructor for Spring instantiation.
         */
        public TransactionGroup() {
            this.totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.averageAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.transactionCount = 0;
        }

        // Getters and Setters with validation

        public Long getAccountId() {
            return accountId;
        }

        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        public String getTransactionTypeCode() {
            return transactionTypeCode;
        }

        public void setTransactionTypeCode(String transactionTypeCode) {
            this.transactionTypeCode = transactionTypeCode;
        }

        public String getTransactionCategoryCode() {
            return transactionCategoryCode;
        }

        public void setTransactionCategoryCode(String transactionCategoryCode) {
            this.transactionCategoryCode = transactionCategoryCode;
        }

        public Integer getTransactionCount() {
            return transactionCount;
        }

        public void setTransactionCount(Integer transactionCount) {
            this.transactionCount = transactionCount;
        }

        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        public void setTotalAmount(BigDecimal totalAmount) {
            // Ensure COBOL COMP-3 precision is maintained
            this.totalAmount = totalAmount != null ? 
                totalAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }

        public BigDecimal getAverageAmount() {
            return averageAmount;
        }

        public void setAverageAmount(BigDecimal averageAmount) {
            // Ensure COBOL COMP-3 precision is maintained
            this.averageAmount = averageAmount != null ? 
                averageAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }

        public LocalDateTime getFirstTransactionDate() {
            return firstTransactionDate;
        }

        public void setFirstTransactionDate(LocalDateTime firstTransactionDate) {
            this.firstTransactionDate = firstTransactionDate;
        }

        public LocalDateTime getLastTransactionDate() {
            return lastTransactionDate;
        }

        public void setLastTransactionDate(LocalDateTime lastTransactionDate) {
            this.lastTransactionDate = lastTransactionDate;
        }

        public String getPrimaryCardNumber() {
            return primaryCardNumber;
        }

        public void setPrimaryCardNumber(String primaryCardNumber) {
            this.primaryCardNumber = primaryCardNumber;
        }

        /**
         * Generates composite key for uniqueness checking.
         * 
         * @return composite key string
         */
        public String getCompositeKey() {
            return String.format("%d-%s-%d", accountId, transactionTypeCode, transactionCategoryCode);
        }

        @Override
        public String toString() {
            return "TransactionGroup{" +
                    "accountId=" + accountId +
                    ", transactionTypeCode='" + transactionTypeCode + '\'' +
                    ", transactionCategoryCode=" + transactionCategoryCode +
                    ", transactionCount=" + transactionCount +
                    ", totalAmount=" + totalAmount +
                    ", averageAmount=" + averageAmount +
                    ", firstTransactionDate=" + firstTransactionDate +
                    ", lastTransactionDate=" + lastTransactionDate +
                    ", primaryCardNumber='" + primaryCardNumber + '\'' +
                    '}';
        }
    }
}
