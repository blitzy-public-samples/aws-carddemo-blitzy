package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalance.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for TransactionCategoryBalance entity.
 * 
 * Replaces COBOL VSAM KSDS file I/O operations on transaction category balance file
 * (CVTRA01Y.cpy) with type-safe database access using Spring Data JPA.
 * 
 * This repository provides CRUD operations and custom query methods for accessing
 * transaction category balance records. The composite primary key structure matches
 * the COBOL TRAN-CAT-KEY from CVTRA01Y.cpy:
 * - TRANCAT-ACCT-ID PIC 9(11) → accountId
 * - TRANCAT-TYPE-CD PIC X(02) → transactionTypeCode  
 * - TRANCAT-CD PIC 9(04) → categoryCode
 * 
 * COBOL Operation Mappings:
 * - EXEC CICS READ FILE(TCATBAL) → findById() or findByAccountIdAndTransactionTypeCodeAndCategoryCode()
 * - EXEC CICS WRITE FILE(TCATBAL) → save()
 * - EXEC CICS REWRITE FILE(TCATBAL) → save() (update)
 * - EXEC CICS DELETE FILE(TCATBAL) → deleteById() or delete()
 * - EXEC CICS STARTBR/READNEXT → findAll() or findByAccountId()
 * 
 * All balance fields use BigDecimal with precision=12, scale=2 to maintain exact
 * COBOL COMP-3 packed decimal precision per section 0.10 requirement 7.
 * 
 * Query methods use Spring Data JPA method naming conventions for automatic
 * query derivation, eliminating the need for @Query annotations for simple queries.
 * 
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
@Repository
public interface TransactionCategoryBalanceRepository extends 
        JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

    /**
     * Find transaction category balance by composite key components.
     * 
     * This method provides an alternative to findById() when the caller has individual
     * key components rather than a TransactionCategoryBalanceId object.
     * 
     * Maps to COBOL VSAM READ operation with full key:
     * MOVE account-id TO TRANCAT-ACCT-ID
     * MOVE type-code TO TRANCAT-TYPE-CD
     * MOVE category-code TO TRANCAT-CD
     * EXEC CICS READ FILE(TCATBAL) INTO(balance-record) RIDFLD(TRAN-CAT-KEY)
     * 
     * @param accountId the account identifier (TRANCAT-ACCT-ID PIC 9(11))
     * @param transactionTypeCode the transaction type code (TRANCAT-TYPE-CD PIC X(02))
     * @param categoryCode the category code (TRANCAT-CD PIC 9(04))
     * @return Optional containing the balance record if found, empty otherwise
     */
    Optional<TransactionCategoryBalance> findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
            Long accountId, String transactionTypeCode, String categoryCode);

    /**
     * Find all transaction category balances for a specific account.
     * 
     * Maps to COBOL VSAM partial key search (STARTBR/READNEXT) with account ID:
     * MOVE account-id TO TRANCAT-ACCT-ID
     * EXEC CICS STARTBR FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY) GENERIC KEYLENGTH(11)
     * Loop: EXEC CICS READNEXT FILE(TCATBAL) INTO(balance-record)
     * 
     * Used for balance aggregation and reporting by account, supporting queries like:
     * "Get all category balances for account 12345678901"
     * 
     * @param accountId the account identifier to search for
     * @return List of all balance records for the account (empty list if none found)
     */
    List<TransactionCategoryBalance> findByIdAccountId(Long accountId);

    /**
     * Find all transaction category balances for a specific transaction type.
     * 
     * Maps to COBOL logic for aggregating balances by transaction type across all accounts.
     * Used in batch processing and reporting to calculate totals by transaction type.
     * 
     * Example use case: Calculate total balance for all "01" (Purchase) transactions
     * across all accounts and categories.
     * 
     * @param transactionTypeCode the transaction type code to search for (2 characters)
     * @return List of all balance records for the transaction type (empty list if none found)
     */
    List<TransactionCategoryBalance> findByIdTransactionTypeCode(String transactionTypeCode);

    /**
     * Find all transaction category balances for a specific category code.
     * 
     * Maps to COBOL logic for aggregating balances by category across all accounts.
     * Used in reporting and analytics to calculate category-specific totals.
     * 
     * Example use case: Calculate total balance for category "0001" (Groceries)
     * across all accounts and transaction types.
     * 
     * @param categoryCode the category code to search for (4 digits as String)
     * @return List of all balance records for the category (empty list if none found)
     */
    List<TransactionCategoryBalance> findByIdCategoryCode(String categoryCode);

    /**
     * Find all transaction category balances for a specific account and transaction type.
     * 
     * Maps to COBOL partial key search with account ID and type code:
     * MOVE account-id TO TRANCAT-ACCT-ID
     * MOVE type-code TO TRANCAT-TYPE-CD
     * EXEC CICS STARTBR FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY) GENERIC KEYLENGTH(13)
     * 
     * Used for detailed balance breakdowns: "Show all category balances for account 
     * 12345678901 and transaction type '01'"
     * 
     * @param accountId the account identifier
     * @param transactionTypeCode the transaction type code
     * @return List of balance records matching the criteria (empty list if none found)
     */
    List<TransactionCategoryBalance> findByIdAccountIdAndIdTransactionTypeCode(
            Long accountId, String transactionTypeCode);

    /**
     * Find all transaction category balances for a specific account and category.
     * 
     * Used for balance queries spanning multiple transaction types within a single
     * category for an account. Supports analytical queries and reporting.
     * 
     * Example: "Show all transaction type balances for account 12345678901 in 
     * category '0001' (Groceries)"
     * 
     * @param accountId the account identifier
     * @param categoryCode the category code
     * @return List of balance records matching the criteria (empty list if none found)
     */
    List<TransactionCategoryBalance> findByIdAccountIdAndIdCategoryCode(
            Long accountId, String categoryCode);

    /**
     * Find all transaction category balances for a specific transaction type and category.
     * 
     * Used for cross-account analysis: calculate total balance for a specific
     * transaction type and category combination across all accounts.
     * 
     * Example: "Calculate total balance for all accounts with transaction type '01'
     * (Purchase) in category '0001' (Groceries)"
     * 
     * @param transactionTypeCode the transaction type code
     * @param categoryCode the category code
     * @return List of balance records matching the criteria (empty list if none found)
     */
    List<TransactionCategoryBalance> findByIdTransactionTypeCodeAndIdCategoryCode(
            String transactionTypeCode, String categoryCode);

    /**
     * Check if a transaction category balance exists for the given composite key.
     * 
     * Maps to COBOL existence check:
     * EXEC CICS READ FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY) 
     *      RESP(ws-resp) RESP2(ws-resp2)
     * IF ws-resp = DFHRESP(NORMAL) THEN...
     * 
     * More efficient than findById() when only existence needs to be checked
     * without retrieving the actual record data.
     * 
     * @param accountId the account identifier
     * @param transactionTypeCode the transaction type code
     * @param categoryCode the category code
     * @return true if a record exists, false otherwise
     */
    boolean existsByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
            Long accountId, String transactionTypeCode, String categoryCode);

    /**
     * Delete transaction category balance by composite key components.
     * 
     * Maps to COBOL VSAM DELETE operation:
     * MOVE account-id TO TRANCAT-ACCT-ID
     * MOVE type-code TO TRANCAT-TYPE-CD
     * MOVE category-code TO TRANCAT-CD
     * EXEC CICS DELETE FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY)
     * 
     * @param accountId the account identifier
     * @param transactionTypeCode the transaction type code
     * @param categoryCode the category code
     */
    void deleteByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
            Long accountId, String transactionTypeCode, String categoryCode);

    /**
     * Delete all transaction category balances for a specific account.
     * 
     * Used in account closure processing or data cleanup operations.
     * Equivalent to COBOL loop: DELETE all records where TRANCAT-ACCT-ID matches.
     * 
     * @param accountId the account identifier
     * @return number of records deleted
     */
    long deleteByIdAccountId(Long accountId);
}
