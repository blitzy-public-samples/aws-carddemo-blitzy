/*
 * TransactionAggregateRepository.java
 *
 * Spring Data JPA repository interface for TransactionAggregate entity.
 * Provides CRUD operations and custom queries for transaction aggregation data access.
 * 
 * This repository enables:
 * - Validation of aggregated results by account, transaction type, and category
 * - Batch job output verification for TransactionAggregationJob
 * - Transaction category summary reporting
 *
 * Migrated from: CBTRN03C.cbl (Transaction Detail Report Batch Program)
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */
package com.carddemo.repository;

import com.carddemo.entity.TransactionAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for TransactionAggregate entity providing database access
 * for transaction aggregation data.
 * 
 * <p>This repository supports the TransactionAggregationJob batch process which
 * aggregates transaction data by account, transaction type, and transaction category.
 * It replaces the aggregation and reporting logic from the COBOL CBTRN03C program.</p>
 * 
 * <p>The repository provides methods for:</p>
 * <ul>
 *   <li>Retrieving aggregations by account ID</li>
 *   <li>Retrieving aggregations by transaction type code</li>
 *   <li>Retrieving aggregations by transaction category code</li>
 *   <li>Finding specific aggregations using composite business key</li>
 *   <li>Calculating total balance by account across all categories</li>
 * </ul>
 * 
 * <p>Usage: This repository is primarily used by:</p>
 * <ul>
 *   <li>TransactionAggregationJob - to persist aggregation results</li>
 *   <li>TransactionAggregationJobTest - to validate batch job outputs</li>
 *   <li>TransactionCategoryService - to generate category summary reports</li>
 * </ul>
 * 
 * @see com.carddemo.entity.TransactionAggregate
 * @see com.carddemo.batch.job.TransactionAggregationJob
 * @see com.carddemo.service.TransactionCategoryService
 */
@Repository
public interface TransactionAggregateRepository extends JpaRepository<TransactionAggregate, TransactionAggregate.AggregateId> {

    /**
     * Retrieves all transaction aggregations for a specific account.
     * 
     * <p>This method returns all aggregation records associated with the given account ID,
     * across all transaction types and categories. Useful for generating complete
     * account-level transaction summaries.</p>
     * 
     * <p>Equivalent COBOL logic: Accumulates WS-ACCOUNT-TOTAL in CBTRN03C for each card/account.</p>
     * 
     * @param accountId the unique identifier of the account
     * @return list of all transaction aggregates for the account; empty list if none found
     */
    List<TransactionAggregate> findById_AccountId(Long accountId);

    /**
     * Retrieves all transaction aggregations for a specific transaction type.
     * 
     * <p>This method returns aggregation records filtered by transaction type code
     * (e.g., "01" for purchases, "02" for cash advances). Useful for analyzing
     * transaction patterns by type across all accounts.</p>
     * 
     * <p>Equivalent COBOL logic: Lookup and reporting by TRAN-TYPE-CD in CBTRN03C.</p>
     * 
     * @param typeCode the transaction type code (2-character code)
     * @return list of all transaction aggregates for the type; empty list if none found
     */
    List<TransactionAggregate> findById_TransactionTypeCode(String typeCode);

    /**
     * Retrieves all transaction aggregations for a specific transaction category.
     * 
     * <p>This method returns aggregation records filtered by transaction category code
     * (e.g., "010001" for groceries, "010002" for gas). Useful for category-based analysis
     * and reporting across all accounts and transaction types.</p>
     * 
     * <p>Equivalent COBOL logic: Lookup and reporting by TRAN-CAT-CD in CBTRN03C.</p>
     * 
     * @param categoryCode the transaction category code (6-character string code, e.g., "010001")
     * @return list of all transaction aggregates for the category; empty list if none found
     */
    List<TransactionAggregate> findById_TransactionCategoryCode(String categoryCode);

    /**
     * Finds a specific transaction aggregation using the composite business key.
     * 
     * <p>This method retrieves a unique aggregation record identified by the combination
     * of account ID, transaction type code, and transaction category code. This represents
     * the natural business key for transaction aggregations.</p>
     * 
     * <p>The composite key ensures that each combination of account, type, and category
     * has exactly one aggregation record, matching the unique constraint in the database.</p>
     * 
     * <p>Equivalent COBOL logic: Represents the unique combination used in CBTRN03C
     * for organizing and totaling transactions by account, type, and category.</p>
     * 
     * @param accountId the unique identifier of the account
     * @param typeCode the transaction type code (2-character code)
     * @param categoryCode the transaction category code (6-character string code)
     * @return Optional containing the matching aggregate if found, empty Optional otherwise
     */
    Optional<TransactionAggregate> findById_AccountIdAndId_TransactionTypeCodeAndId_TransactionCategoryCode(
            Long accountId, 
            String typeCode, 
            String categoryCode
    );

    /**
     * Calculates the total balance across all transaction categories for a specific account.
     * 
     * <p>This method sums the category_balance field across all transaction types and
     * categories for a given account, providing the overall transaction total for that account.
     * This is useful for account-level financial summaries and validation.</p>
     * 
     * <p>Equivalent COBOL logic: Final value of WS-ACCOUNT-TOTAL in CBTRN03C after
     * accumulating all transactions for a card/account number.</p>
     * 
     * <p>Returns zero if no aggregation records exist for the account.</p>
     * 
     * @param accountId the unique identifier of the account
     * @return the sum of all category balances for the account; zero if no records found
     */
    @Query("SELECT COALESCE(SUM(ta.categoryBalance), 0) FROM TransactionAggregate ta WHERE ta.id.accountId = :accountId")
    BigDecimal getTotalBalanceByAccount(@Param("accountId") Long accountId);

    /**
     * Finds all transaction aggregations for multiple accounts.
     * 
     * <p>This method provides batch retrieval of aggregations for a list of account IDs,
     * useful for generating multi-account reports and batch validation operations.</p>
     * 
     * @param accountIds collection of account identifiers
     * @return list of all transaction aggregates for the specified accounts; empty list if none found
     */
    List<TransactionAggregate> findById_AccountIdIn(List<Long> accountIds);

    /**
     * Finds all transaction aggregations matching a specific type and category combination.
     * 
     * <p>This method retrieves aggregations across all accounts for a specific
     * transaction type and category pair, useful for cross-account category analysis.</p>
     * 
     * @param typeCode the transaction type code (2-character string, e.g., "01")
     * @param categoryCode the transaction category code (6-character string, e.g., "010001")
     * @return list of matching aggregates across all accounts; empty list if none found
     */
    List<TransactionAggregate> findById_TransactionTypeCodeAndId_TransactionCategoryCode(
            String typeCode,
            String categoryCode
    );

    /**
     * Calculates the grand total of all transaction aggregations.
     * 
     * <p>This method sums all category balances across all accounts, types, and categories,
     * providing the system-wide total for transaction aggregations.</p>
     * 
     * <p>Equivalent COBOL logic: Final value of WS-GRAND-TOTAL in CBTRN03C after
     * processing all transactions in the report.</p>
     * 
     * @return the sum of all category balances system-wide; zero if no records exist
     */
    @Query("SELECT COALESCE(SUM(ta.categoryBalance), 0) FROM TransactionAggregate ta")
    BigDecimal getGrandTotal();

    /**
     * Counts the number of aggregation records for a specific account.
     * 
     * <p>This method returns the count of distinct type/category combinations
     * for a given account, useful for validation and reporting purposes.</p>
     * 
     * @param accountId the unique identifier of the account
     * @return the count of aggregation records for the account
     */
    long countById_AccountId(Long accountId);
}
