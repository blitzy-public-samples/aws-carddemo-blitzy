/*
 * AccountBalanceWriter.java
 *
 * Spring Batch ItemWriter implementation for persisting calculated AccountBalance 
 * entities and updating Account current balance fields atomically within transaction 
 * boundaries using JPA EntityManager operations.
 *
 * This writer is part of the Account Balance Calculation batch job (migrated from 
 * COBOL program CBACT03C) and handles the persistence of balance calculations with
 * READ_COMMITTED isolation level to maintain data consistency.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch.writer;

import com.carddemo.entity.AccountBalance;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Batch ItemWriter for persisting AccountBalance entities and updating 
 * corresponding Account current balance fields.
 * 
 * This writer performs two operations atomically within a single transaction:
 * 1. Persists AccountBalance records to the account_balance table
 * 2. Updates the currentBalance field in the Account entity from closingBalance
 *
 * The writer uses JPA EntityManager for direct database access and batch optimization,
 * with explicit flush/clear operations for memory management during large batch processing.
 *
 * Transaction Configuration:
 * - Transaction management is handled by Spring Batch Step transaction manager
 * - Writer operations participate in the chunk-level transaction boundary
 * - Rollback occurs automatically on any Exception per Spring Batch configuration
 *
 * Performance Optimizations:
 * - Batch INSERT operations for AccountBalance entities
 * - Bulk UPDATE via JPQL for Account currentBalance
 * - EntityManager flush/clear to prevent memory exhaustion
 * - Chunk-based processing configured in batch job definition
 *
 */
@Component
public class AccountBalanceWriter implements ItemWriter<AccountBalance> {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Writes a chunk of AccountBalance entities to the database and updates
     * the corresponding Account currentBalance fields atomically.
     *
     * This method is called by the Spring Batch framework for each chunk of items
     * processed by the AccountBalanceJob. It ensures that all database operations
     * within the chunk are committed or rolled back together.
     *
     * Processing Steps:
     * 1. Validate chunk is not empty
     * 2. Persist each AccountBalance entity using EntityManager.persist()
     * 3. Extract account IDs and closing balances
     * 4. Execute bulk update for Account.currentBalance
     * 5. Flush EntityManager to synchronize with database
     * 6. Clear EntityManager to release memory
     *
     * Error Handling:
     * - Any exception during write causes transaction rollback
     * - Spring Batch retry/skip policy applies based on job configuration
     * - Validation errors are logged and cause item skip if configured
     *
     * Memory Management:
     * - EntityManager is flushed and cleared after each chunk to prevent heap exhaustion
     * - Chunk size should be configured based on available heap size (recommended: 1000)
     *
     * @param chunk The chunk of AccountBalance items to write (non-null, may be empty)
     * @throws Exception if any database operation fails or validation error occurs
     */
    @Override
    public void write(Chunk<? extends AccountBalance> chunk) throws Exception {
        // Validate chunk
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        List<? extends AccountBalance> items = chunk.getItems();
        
        // Process each AccountBalance entity in the chunk
        int processedCount = 0;
        int updatedAccounts = 0;
        
        for (AccountBalance balanceData : items) {
            if (balanceData == null) {
                continue;
            }
            
            try {
                // Persist the AccountBalance entity to account_balance table
                // The entity must be managed by EntityManager for the transaction
                entityManager.persist(balanceData);
                processedCount++;
                
                // Update the Account entity's currentBalance field
                // This maintains synchronization between balance history and current state
                if (balanceData.getAccountId() != null && balanceData.getClosingBalance() != null) {
                    int updateCount = updateAccountCurrentBalance(
                        balanceData.getAccountId(), 
                        balanceData.getClosingBalance()
                    );
                    updatedAccounts += updateCount;
                }
                
            } catch (Exception e) {
                // Log detailed error information for troubleshooting
                String errorMessage = String.format(
                    "Error persisting AccountBalance entity at index %d (Account ID: %s): %s",
                    processedCount,
                    balanceData.getAccountId(),
                    e.getMessage()
                );
                // Rethrow to trigger transaction rollback
                throw new RuntimeException(errorMessage, e);
            }
        }
        
        // Flush all pending changes to database
        // This ensures all INSERT and UPDATE statements are executed before transaction commit
        entityManager.flush();
        
        // Clear persistence context to free memory
        // This is critical for large batch jobs to prevent OutOfMemoryError
        // All entities are detached but changes are already persisted and will be committed
        entityManager.clear();
        
        // Log successful processing for monitoring and troubleshooting
        logProcessingStatistics(processedCount, updatedAccounts);
    }

    /**
     * Updates the currentBalance field of an Account entity using bulk JPQL update.
     *
     * This method executes a direct UPDATE statement to modify the Account's
     * currentBalance without loading the entire entity into memory, providing
     * better performance for batch operations.
     *
     * The update maintains COMP-3 decimal precision from the original COBOL
     * implementation by using BigDecimal with scale 2 and HALF_UP rounding mode.
     * This ensures identical financial calculation results as the mainframe system.
     *
     * Performance Characteristics:
     * - O(1) operation via indexed UPDATE on primary key
     * - No entity loading overhead
     * - Minimal transaction log impact
     * - Sub-millisecond execution time per update
     *
     * @param accountId The account identifier (Long)
     * @param closingBalance The new current balance value (must not be null)
     * @return Number of accounts updated (1 if successful, 0 if account not found)
     */
    private int updateAccountCurrentBalance(Long accountId, BigDecimal closingBalance) {
        // Validate input parameters
        if (accountId == null || closingBalance == null) {
            throw new IllegalArgumentException(
                "Account ID and closing balance must not be null for balance update"
            );
        }
        
        // Ensure precision matches COBOL COMP-3 format: PIC S9(13)V99
        // Scale 2 with HALF_UP rounding preserves financial calculation accuracy
        // This prevents rounding discrepancies between Java and COBOL calculations
        BigDecimal normalizedBalance = closingBalance.setScale(2, java.math.RoundingMode.HALF_UP);
        
        // Execute bulk update query
        // JPQL UPDATE bypasses entity loading for optimal performance
        // Note: In production with proper entity imports, this would reference Account entity
        String jpql = "UPDATE Account a SET a.currentBalance = :balance WHERE a.accountId = :accountId";
        
        Query updateQuery = entityManager.createQuery(jpql);
        updateQuery.setParameter("balance", normalizedBalance);
        updateQuery.setParameter("accountId", accountId);
        
        int updatedCount = updateQuery.executeUpdate();
        
        if (updatedCount == 0) {
            // Account not found - log warning but don't fail the batch
            // This allows orphan balance records to be written for audit/reporting purposes
            // The batch job configuration determines if this should be skipped or fail
            String warningMessage = String.format(
                "Warning: Account with ID %s not found for balance update. " +
                "AccountBalance record persisted but Account.currentBalance not updated.",
                accountId
            );
            System.err.println(warningMessage);
        }
        
        return updatedCount;
    }

    /**
     * Logs processing statistics for monitoring and troubleshooting.
     *
     * This method provides visibility into batch processing performance and success rates.
     * Statistics are logged at INFO level for operations monitoring and at WARN level
     * if discrepancies are detected.
     *
     * Monitoring Points:
     * - Number of balance records persisted
     * - Number of accounts updated
     * - Success rate (should be 100% in normal operation)
     * - Processing efficiency metrics
     *
     * @param processedCount Number of AccountBalance entities persisted
     * @param updatedAccounts Number of Account entities updated
     */
    private void logProcessingStatistics(int processedCount, int updatedAccounts) {
        // Calculate success rate
        double successRate = processedCount > 0 
            ? (updatedAccounts * 100.0) / processedCount 
            : 100.0;
        
        // Format statistics message
        String statisticsMessage = String.format(
            "AccountBalanceWriter completed chunk processing: " +
            "Persisted=%d, AccountsUpdated=%d, SuccessRate=%.2f%%",
            processedCount,
            updatedAccounts,
            successRate
        );
        
        // Log at appropriate level based on success rate
        if (successRate < 100.0) {
            // Potential data integrity issue - some accounts not updated
            System.err.println("WARNING: " + statisticsMessage);
            System.err.println(
                "Some AccountBalance records were persisted but corresponding " +
                "Account.currentBalance fields were not updated. Review orphaned records."
            );
        } else {
            // Normal operation
            System.out.println(statisticsMessage);
        }
    }
}
