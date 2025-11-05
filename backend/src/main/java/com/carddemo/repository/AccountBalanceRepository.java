package com.carddemo.repository;

import com.carddemo.constants.BalanceType;
import com.carddemo.entity.AccountBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for AccountBalance entity data access operations.
 * 
 * <p>This repository provides CRUD operations and custom query methods for managing
 * AccountBalance entities in the PostgreSQL database. It transforms VSAM ACCTBAL file
 * access patterns to Spring Data JPA repository methods, enabling efficient balance
 * history queries, point-in-time balance retrieval, and balance reconciliation operations.</p>
 * 
 * <p><b>Primary Use Cases:</b></p>
 * <ul>
 *   <li>CBACT03C Batch Job: Persist historical balance snapshots at end-of-day</li>
 *   <li>CBACT04C Batch Job: Retrieve balance history for interest calculations</li>
 *   <li>CBSTM03A Batch Job: Query statement period balances for statement generation</li>
 *   <li>Account View Service: Display current and historical account balances</li>
 *   <li>Balance Reconciliation: Compare calculated vs. stored balances for audit</li>
 * </ul>
 * 
 * <p><b>COBOL Migration Context:</b></p>
 * <p>This repository replaces COBOL file I/O operations on the VSAM ACCTBAL file,
 * providing equivalent functionality through Spring Data JPA abstractions per
 * Section 0.3 transformation rules.</p>
 * 
 * <p><b>Query Performance:</b></p>
 * <ul>
 *   <li>Primary key queries use index on account_balance_id (auto-generated)</li>
 *   <li>Account history queries use composite index on (account_id, effective_date)</li>
 *   <li>Date range queries use index on effective_date for efficient filtering</li>
 *   <li>Balance type filtering leverages CHAR(1) column for fast equality checks</li>
 * </ul>
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 * @see AccountBalance
 * @see com.carddemo.batch.job.AccountBalanceJob
 * @see com.carddemo.batch.writer.AccountBalanceWriter
 */
@Repository
public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {
    
    /**
     * Finds all balance records for a specific account ordered by effective date descending.
     * 
     * <p>This method retrieves the complete balance history for an account, with the most
     * recent balances first. It supports account balance history displays and trend analysis
     * in the user interface and reporting modules.</p>
     * 
     * <p><b>Query Performance:</b> Uses composite index on (account_id, effective_date DESC)
     * for optimal query execution.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Retrieve balance history for account
     * List&lt;AccountBalance&gt; history = repository.findByAccountIdOrderByEffectiveDateDesc(accountId);
     * </pre>
     * 
     * @param accountId the account ID to retrieve balances for (11-digit account number)
     * @return List of AccountBalance records sorted by effective date (newest first)
     */
    List<AccountBalance> findByAccountIdOrderByEffectiveDateDesc(Long accountId);
    
    /**
     * Finds all balance records for a specific account and balance type.
     * 
     * <p>This method enables filtering balance history by type (CURRENT, AVAILABLE,
     * PENDING, HISTORICAL), supporting specialized queries such as retrieving only
     * historical snapshots for audit or only current balances for real-time displays.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Retrieve only historical balance snapshots
     * List&lt;AccountBalance&gt; snapshots = repository.findByAccountIdAndBalanceType(
     *     accountId,
     *     BalanceType.HISTORICAL
     * );
     * </pre>
     * 
     * @param accountId the account ID to filter by
     * @param balanceType the BalanceType enum value (CURRENT, AVAILABLE, PENDING, HISTORICAL)
     * @return List of AccountBalance records matching the account and balance type criteria
     */
    List<AccountBalance> findByAccountIdAndBalanceType(Long accountId, BalanceType balanceType);
    
    /**
     * Finds the most recent balance record for an account on a specific effective date.
     * 
     * <p>This method supports point-in-time balance queries, essential for regulatory
     * reporting, balance reconciliation, and historical analysis. It retrieves the balance
     * snapshot taken on the specified date for a given account.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Retrieve end-of-day balance for specific date
     * Optional&lt;AccountBalance&gt; balance = repository.findByAccountIdAndEffectiveDate(
     *     accountId,
     *     LocalDate.of(2024, 1, 31)
     * );
     * </pre>
     * 
     * @param accountId the account ID to query
     * @param effectiveDate the effective date of the balance snapshot
     * @return Optional containing the AccountBalance if found, empty otherwise
     */
    Optional<AccountBalance> findByAccountIdAndEffectiveDate(Long accountId, LocalDate effectiveDate);
    
    /**
     * Finds balance records for an account within a date range, ordered by effective date.
     * 
     * <p>This method supports statement generation, balance reconciliation, and period-based
     * reporting by retrieving all balance snapshots within a specified date range. It is
     * used by CBSTM03A (Statement Generation) to gather monthly balance history.</p>
     * 
     * <p><b>Query Performance:</b> Uses composite index on (account_id, effective_date)
     * with range scan for efficient date filtering.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Retrieve monthly balance history for statement
     * List&lt;AccountBalance&gt; monthlyBalances = repository.findByAccountIdAndEffectiveDateBetween(
     *     accountId,
     *     LocalDate.of(2024, 1, 1),
     *     LocalDate.of(2024, 1, 31)
     * );
     * </pre>
     * 
     * @param accountId the account ID to filter by
     * @param startDate the start date of the range (inclusive)
     * @param endDate the end date of the range (inclusive)
     * @return List of AccountBalance records within the date range, sorted by effective date
     */
    List<AccountBalance> findByAccountIdAndEffectiveDateBetweenOrderByEffectiveDateAsc(
        Long accountId,
        LocalDate startDate,
        LocalDate endDate
    );
    
    /**
     * Finds the most recent balance record for an account before or on a specific date.
     * 
     * <p>This method supports historical balance lookups where the exact effective date
     * may not be known or where the last known balance before a date is needed. It is
     * useful for balance reconstruction and reconciliation operations.</p>
     * 
     * <p><b>Query Implementation:</b> Uses native query with MAX(effective_date) where
     * effective_date <= :date to find the most recent balance before or on the specified date.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Find last known balance before statement date
     * Optional&lt;AccountBalance&gt; lastBalance = repository.findLatestByAccountIdBeforeDate(
     *     accountId,
     *     statementDate
     * );
     * </pre>
     * 
     * @param accountId the account ID to query
     * @param date the cutoff date (balances on or before this date are considered)
     * @return Optional containing the most recent AccountBalance before the date, empty if none found
     */
    @Query("SELECT ab FROM AccountBalance ab " +
           "WHERE ab.accountId = :accountId " +
           "AND ab.effectiveDate <= :date " +
           "ORDER BY ab.effectiveDate DESC " +
           "LIMIT 1")
    Optional<AccountBalance> findLatestByAccountIdBeforeDate(
        @Param("accountId") Long accountId,
        @Param("date") LocalDate date
    );
    
    /**
     * Deletes all balance records for a specific account.
     * 
     * <p>This method supports account closure operations and test data cleanup. It removes
     * all balance history for an account, typically used when an account is permanently
     * closed or during test teardown operations.</p>
     * 
     * <p><b>CAUTION:</b> This operation is irreversible and removes all balance audit history
     * for the account. Ensure regulatory retention requirements are met before using.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Clean up balance history for closed account (after retention period)
     * repository.deleteByAccountId(accountId);
     * </pre>
     * 
     * @param accountId the account ID whose balance records should be deleted
     */
    void deleteByAccountId(Long accountId);
    
    /**
     * Counts the number of balance records for a specific account.
     * 
     * <p>This method provides a quick count of balance history entries without retrieving
     * the actual records. It supports pagination and performance monitoring of balance
     * history accumulation.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Check balance history size before retrieval
     * long historyCount = repository.countByAccountId(accountId);
     * if (historyCount > 1000) {
     *     // Apply pagination or filtering
     * }
     * </pre>
     * 
     * @param accountId the account ID to count balance records for
     * @return the number of balance records for the specified account
     */
    long countByAccountId(Long accountId);
}
