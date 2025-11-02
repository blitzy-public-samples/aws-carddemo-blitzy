/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Account;
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
 * Spring Data JPA Repository for Account entity.
 * 
 * <p>This repository replaces COBOL VSAM ACCTDAT file access patterns with
 * relational database queries. It provides CRUD operations and custom query
 * methods for account data access.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>VSAM KSDS ACCTDAT file → PostgreSQL account table</li>
 *   <li>Sequential READ operations → Paginated findAll queries</li>
 *   <li>Direct READ by key → findById method</li>
 *   <li>VSAM alternate indexes → Database secondary indexes</li>
 * </ul>
 * 
 * <p><b>Custom Queries:</b></p>
 * <ul>
 *   <li>findActiveAccountsForStatementPeriod: Accounts needing statements</li>
 *   <li>findByCustomerId: All accounts for a customer</li>
 *   <li>findByActiveStatus: Accounts by status</li>
 *   <li>findByGroupId: Accounts in a group</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Finds active accounts requiring statement generation for a given period.
     * 
     * <p>This method implements the account selection criteria from COBOL
     * CBSTM03A.CBL statement generation logic. It selects accounts that:</p>
     * <ul>
     *   <li>Have active status ('A')</li>
     *   <li>Have transactions in the statement period OR have non-zero balance</li>
     *   <li>Are not flagged for statement suppression</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b> XREFFILE sequential read with ACCT-ACTIVE-STATUS check
     * (lines 317-329 in CBSTM03A.CBL)</p>
     * 
     * <p><b>Performance Optimization:</b></p>
     * <ul>
     *   <li>Uses EXISTS subquery for transaction check to avoid loading all transactions</li>
     *   <li>Indexed on active_status for fast filtering</li>
     *   <li>Supports pagination to process large datasets in chunks</li>
     *   <li>Orders by account ID for consistent pagination</li>
     * </ul>
     * 
     * @param startDate Start date of statement period (inclusive)
     * @param endDate End date of statement period (inclusive)
     * @param pageable Pagination parameters (page number, size, sort)
     * @return Page of accounts requiring statement generation
     */
    @Query("SELECT DISTINCT a FROM Account a " +
           "LEFT JOIN Transaction t ON t.accountId = a.id " +
           "WHERE a.activeStatus = 'A' " +
           "AND (t.transactionDate BETWEEN :startDate AND :endDate " +
           "     OR a.currentBalance <> 0) " +
           "ORDER BY a.id ASC")
    Page<Account> findActiveAccountsForStatementPeriod(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    /**
     * Finds active accounts requiring statement generation after a specific account ID.
     * 
     * <p>This method supports restart capability by allowing the batch job to resume
     * processing from the last successfully processed account. Used during job restart
     * scenarios to skip already-processed accounts.</p>
     * 
     * <p><b>Usage:</b> Called by AccountStatementReader during restart to fetch accounts
     * after the last checkpoint.</p>
     * 
     * @param startDate Start date of statement period (inclusive)
     * @param endDate End date of statement period (inclusive)
     * @param lastAccountId Last successfully processed account ID
     * @param pageable Pagination parameters
     * @return Page of accounts with ID greater than lastAccountId
     */
    @Query("SELECT DISTINCT a FROM Account a " +
           "LEFT JOIN Transaction t ON t.accountId = a.id " +
           "WHERE a.activeStatus = 'A' " +
           "AND a.id > :lastAccountId " +
           "AND (t.transactionDate BETWEEN :startDate AND :endDate " +
           "     OR a.currentBalance <> 0) " +
           "ORDER BY a.id ASC")
    Page<Account> findActiveAccountsForStatementPeriodAfterAccountId(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("lastAccountId") Long lastAccountId,
        Pageable pageable
    );

    /**
     * Finds all accounts for a specific customer.
     * 
     * <p><b>COBOL Equivalent:</b> XREF file lookup by customer ID</p>
     * 
     * @param customerId Customer identifier
     * @return List of accounts belonging to the customer
     */
    List<Account> findByCustomerId(Long customerId);

    /**
     * Finds all accounts with a specific active status.
     * 
     * <p><b>COBOL Equivalent:</b> ACCT-ACTIVE-STATUS field check</p>
     * 
     * @param activeStatus Active status ('A' or 'C')
     * @param pageable Pagination parameters
     * @return Page of accounts with the specified status
     */
    Page<Account> findByActiveStatus(String activeStatus, Pageable pageable);

    /**
     * Finds all accounts in a specific account group.
     * 
     * <p><b>COBOL Equivalent:</b> ACCT-GROUP-ID field filter</p>
     * 
     * @param groupId Account group identifier
     * @return List of accounts in the group
     */
    List<Account> findByGroupId(String groupId);

    /**
     * Finds an account by ID with optional handling.
     * 
     * <p><b>COBOL Equivalent:</b> Direct READ by ACCT-ID key</p>
     * 
     * @param id Account identifier
     * @return Optional containing account if found, empty otherwise
     */
    @Override
    Optional<Account> findById(Long id);

    /**
     * Counts active accounts.
     * 
     * @return Number of active accounts
     */
    Long countByActiveStatus(String activeStatus);

    /**
     * Finds accounts by customer ID and active status.
     * 
     * @param customerId Customer identifier
     * @param activeStatus Active status
     * @return List of matching accounts
     */
    List<Account> findByCustomerIdAndActiveStatus(Long customerId, String activeStatus);

    /**
     * Finds accounts with credit limit greater than specified amount.
     * 
     * @param creditLimit Minimum credit limit
     * @param pageable Pagination parameters
     * @return Page of accounts with higher credit limits
     */
    @Query("SELECT a FROM Account a WHERE a.creditLimit > :creditLimit ORDER BY a.creditLimit DESC")
    Page<Account> findAccountsWithCreditLimitGreaterThan(
        @Param("creditLimit") java.math.BigDecimal creditLimit,
        Pageable pageable
    );

    /**
     * Finds accounts with expiration date before specified date.
     * 
     * @param date Expiration date threshold
     * @return List of accounts expiring before the date
     */
    @Query("SELECT a FROM Account a WHERE a.expirationDate < :date AND a.activeStatus = 'A'")
    List<Account> findAccountsExpiringBefore(@Param("date") LocalDate date);

    /**
     * Finds accounts opened within a date range.
     * 
     * @param startDate Start of date range
     * @param endDate End of date range
     * @return List of accounts opened in the range
     */
    @Query("SELECT a FROM Account a WHERE a.openDate BETWEEN :startDate AND :endDate ORDER BY a.openDate ASC")
    List<Account> findAccountsOpenedBetween(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
}
