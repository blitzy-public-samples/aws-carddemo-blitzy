/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for Transaction entity providing comprehensive CRUD operations,
 * complex queries, pagination support, and aggregate functions for transaction data access.
 * <p>
 * This repository replaces VSAM TRANSACT file operations with relational database access patterns,
 * replacing sequential and random access patterns from COBOL programs COTRN00C, COTRN01C, COTRN02C,
 * and batch programs CBTRN01C, CBTRN02C, CBTRN03C.
 * </p>
 * <p>
 * Key Methods:
 * - findByTransactionId(): Transaction lookup (replaces CICS READ DATASET TRANSACT)
 * - findByAccountId(): Account-based transaction retrieval with pagination (10 per page per COTRN00C)
 * - findByCardNumber(): Card-based transaction history
 * - findByTransactionDateBetween(): Date range queries for statement generation
 * - aggregateByCategory(): Category-based SUM replacing COBOL COTRN01C aggregation logic
 * </p>
 *
 * @see Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Finds a transaction by its unique transaction ID.
     * <p>
     * Replaces: EXEC CICS READ DATASET(TRANSACT) RIDFLD(transaction-id) from COBOL programs
     * </p>
     *
     * @param transactionId the unique 16-character transaction identifier
     * @return Optional containing the transaction if found, empty otherwise
     */
    Optional<Transaction> findByTransactionId(String transactionId);

    /**
     * Retrieves all transactions for a specific account (non-paginated).
     * <p>
     * Used by batch processing jobs that need to process all transactions
     * for an account, such as balance calculation and aggregation jobs.
     * </p>
     *
     * @param accountId the 11-digit account identifier
     * @return List of all transactions for the account
     */
    @Query("SELECT t FROM Transaction t WHERE t.card.account.accountId = :accountId")
    List<Transaction> findByAccountId(@Param("accountId") Long accountId);

    /**
     * Retrieves paginated transactions for a specific account.
     * <p>
     * Replaces: COTRN00C.cbl STARTBR/READNEXT browsing pattern (lines 593-668)
     * Pagination: 10 transactions per page per COTRN00C requirements
     * </p>
     *
     * @param accountId the 11-digit account identifier
     * @param pageable  pagination information (page number, size, sort)
     * @return Page of transactions for the account
     */
    @Query("SELECT t FROM Transaction t WHERE t.card.account.accountId = :accountId")
    Page<Transaction> findByAccountId(@Param("accountId") Long accountId, Pageable pageable);

    /**
     * Retrieves paginated transactions for a specific card number.
     * <p>
     * Used for card-based transaction history displays
     * </p>
     *
     * @param cardNumber the 16-digit card number
     * @param pageable   pagination information
     * @return Page of transactions for the card
     */
    Page<Transaction> findByCardNumber(String cardNumber, Pageable pageable);

    /**
     * Finds transactions within a date range for statement generation and reporting.
     * <p>
     * Replaces: COBOL CEEDAYS date arithmetic for statement transaction selection
     * Used by: StatementGenerationJob (CBSTM03A replacement)
     * </p>
     *
     * @param startDate the start date of the range (inclusive)
     * @param endDate   the end date of the range (inclusive)
     * @return List of transactions within the date range
     */
    @Query("SELECT t FROM Transaction t WHERE CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate")
    List<Transaction> findByTransactionDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Retrieves paginated transactions for an account within a date range.
     * <p>
     * Combined filter for account-based transaction history with date filtering
     * </p>
     *
     * @param accountId the account identifier
     * @param startDate the start date (inclusive)
     * @param endDate   the end date (inclusive)
     * @param pageable  pagination information
     * @return Page of transactions matching the criteria
     */
    @Query("SELECT t FROM Transaction t WHERE t.card.account.accountId = :accountId AND CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate")
    Page<Transaction> findByAccountIdAndTransactionDateBetween(
            @Param("accountId") Long accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable
    );

    /**
     * Aggregates transaction amounts by category for a given account and date range.
     * <p>
     * Replaces: COTRN01C.cbl category aggregation logic with PERFORM loops and accumulators
     * Returns: List of Object arrays where [0] = categoryCode, [1] = SUM(amount)
     * </p>
     *
     * @param accountId the account identifier
     * @param startDate the start date for aggregation
     * @param endDate   the end date for aggregation
     * @return List of category code and sum pairs
     */
    @Query("SELECT t.transactionCategoryCode, SUM(t.transactionAmount) " +
           "FROM Transaction t " +
           "WHERE t.card.account.accountId = :accountId " +
           "AND CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate " +
           "GROUP BY t.transactionCategoryCode")
    List<Object[]> aggregateByCategory(
            @Param("accountId") Long accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Counts daily transactions for an account on a specific date.
     * <p>
     * Used for transaction volume monitoring and duplicate detection
     * </p>
     *
     * @param accountId the account identifier
     * @param date      the transaction date
     * @return count of transactions for the account on the specified date
     */
    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.card.account.accountId = :accountId " +
           "AND CAST(t.originationTimestamp AS date) = :date")
    long countDailyTransactions(
            @Param("accountId") Long accountId,
            @Param("date") LocalDate date
    );

    /**
     * Checks if a transaction with the given ID already exists.
     * <p>
     * Used for duplicate transaction detection during batch loading
     * </p>
     *
     * @param transactionId the unique transaction identifier
     * @return true if transaction exists, false otherwise
     */
    boolean existsByTransactionId(String transactionId);
}
