package com.carddemo.repository;

import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.entity.TransactionCategoryBalance.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository interface for TransactionCategoryBalance entity.
 * 
 * Converted from: VSAM TCATBAL KSDS file I/O operations (COBOL EXEC CICS commands)
 * COBOL copybook: CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD)
 * Original file: AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
 * Record length: 50 bytes
 * Key structure: TRAN-CAT-KEY (17 bytes) = TRANCAT-ACCT-ID(11) + TRANCAT-TYPE-CD(2) + TRANCAT-CD(4)
 * 
 * This repository replaces all COBOL VSAM file I/O operations with Spring Data JPA
 * database persistence methods, providing CRUD operations for transaction category
 * balance aggregates. Category balances track running totals by account, transaction
 * type, and category combination for spending pattern analysis and reporting.
 * 
 * VSAM to PostgreSQL Migration Details:
 * -----------------------------------
 * COBOL VSAM Operations → Spring Data JPA Methods:
 * - EXEC CICS READ FILE('TCATBAL') RIDFLD(...) INTO(...)
 *   → findById(TransactionCategoryBalanceId id)
 * - EXEC CICS WRITE FILE('TCATBAL') FROM(...) RIDFLD(...)
 *   → save(TransactionCategoryBalance balance) [for inserts]
 * - EXEC CICS REWRITE FILE('TCATBAL') FROM(...)
 *   → save(TransactionCategoryBalance balance) [for updates]
 * - EXEC CICS DELETE FILE('TCATBAL') RIDFLD(...)
 *   → deleteById(TransactionCategoryBalanceId id) or delete(TransactionCategoryBalance balance)
 * 
 * Composite Key Mapping:
 * ---------------------
 * COBOL TRAN-CAT-KEY (composite VSAM key):
 *   - TRANCAT-ACCT-ID (PIC 9(11))        → tcatAcctId (Long)
 *   - TRANCAT-TYPE-CD (PIC X(02))        → tcatTypeCd (String)
 *   - TRANCAT-CD (PIC 9(04))             → tcatCatCd (Integer)
 * 
 * JPA @IdClass composite key (TransactionCategoryBalanceId):
 *   - Contains all three fields with matching names to entity @Id fields
 *   - Used as the ID type parameter in JpaRepository<Entity, ID>
 *   - Enables findById(new TransactionCategoryBalanceId(acctId, typeCd, catCd))
 * 
 * Data Precision Preservation (per Section 0.7.2):
 * ------------------------------------------------
 * - COBOL PIC S9(09)V99 COMP-3 TRAN-CAT-BAL field converted to BigDecimal
 * - PostgreSQL NUMERIC(11,2) preserves exact packed decimal precision
 * - All balance calculations maintain bit-identical results to COBOL arithmetic
 * - Default balance value: 0.00 (matching COBOL initialization)
 * 
 * Primary Usage Context:
 * ---------------------
 * 1. Batch Job CBTRN03C (Transaction Category Summarization):
 *    - Reads daily transactions and updates category balances
 *    - Uses findByTcatAcctId() to retrieve all categories for an account
 *    - Uses save() to update balances after transaction aggregation
 * 
 * 2. Report Generation Processes:
 *    - Query category balances for spending analysis reports
 *    - Uses findByTcatAcctIdAndTcatTypeCd() to filter by transaction type
 *    - Uses findByTcatAcctIdAndTcatCatCd() to filter by specific category
 * 
 * 3. Account Balance Reconciliation:
 *    - Validate that sum of category balances matches account balance
 *    - Retrieve all categories for verification during daily processing
 * 
 * Performance Considerations:
 * --------------------------
 * - PostgreSQL B-tree indexes created on primary key (tcat_acct_id, tcat_type_cd, tcat_cat_cd)
 * - Composite index supports efficient queries by account ID prefix (findByTcatAcctId)
 * - Additional indexes on frequently queried combinations (account+type, account+category)
 * - Query performance meets/exceeds VSAM key access response times (sub-10ms for primary key)
 * 
 * Transaction Management:
 * ----------------------
 * - All repository methods participate in Spring @Transactional boundaries
 * - COBOL EXEC CICS SYNCPOINT replaced by Spring transaction commit
 * - COBOL EXEC CICS ROLLBACK replaced by Spring transaction rollback on exception
 * - Maintains identical transaction isolation levels to CICS unit-of-work
 * 
 * Custom Query Methods:
 * --------------------
 * Spring Data JPA automatically implements custom query methods based on method naming
 * conventions. No manual implementation required - JPA generates optimized SQL queries.
 * 
 * @see TransactionCategoryBalance entity class for field mappings
 * @see TransactionCategoryBalanceId composite key class
 * @see Section 0.3.4 for database schema design (transaction_category_balance table)
 * @see Section 0.4.8 for repository layer transformation mapping
 * @see CVTRA01Y.cpy for original COBOL copybook structure
 * @see CBTRN03C.cbl for batch job that updates category balances
 */
@Repository
public interface TransactionCategoryBalanceRepository extends 
        JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
    
    /**
     * Find all transaction category balances for a specific account.
     * 
     * Replaces COBOL sequential file browsing pattern where batch jobs read all
     * category balance records for an account to perform aggregation, validation,
     * or reporting operations.
     * 
     * COBOL pattern replaced:
     * <pre>
     * MOVE ACCOUNT-ID TO TRANCAT-ACCT-ID
     * MOVE LOW-VALUES TO TRANCAT-TYPE-CD
     * MOVE LOW-VALUES TO TRANCAT-CD
     * EXEC CICS STARTBR FILE('TCATBAL') RIDFLD(TRAN-CAT-KEY) END-EXEC
     * PERFORM UNTIL EOF
     *   EXEC CICS READNEXT FILE('TCATBAL') INTO(TRAN-CAT-RECORD) RIDFLD(TRAN-CAT-KEY) END-EXEC
     *   IF TRANCAT-ACCT-ID NOT = ACCOUNT-ID
     *     SET EOF TO TRUE
     *   ELSE
     *     ... process category balance ...
     *   END-IF
     * END-PERFORM
     * EXEC CICS ENDBR FILE('TCATBAL') END-EXEC
     * </pre>
     * 
     * Java replacement:
     * <pre>
     * List&lt;TransactionCategoryBalance&gt; balances = repository.findByTcatAcctId(accountId);
     * for (TransactionCategoryBalance balance : balances) {
     *     // process category balance
     * }
     * </pre>
     * 
     * Generated SQL (optimized by JPA):
     * <pre>
     * SELECT * FROM transaction_category_balance 
     * WHERE tcat_acct_id = ?
     * ORDER BY tcat_type_cd, tcat_cat_cd
     * </pre>
     * 
     * Usage in batch processing (CBTRN03C):
     * - Retrieve all category balances for an account during daily processing
     * - Update each category balance based on posted transactions
     * - Validate that sum of all category balances equals account balance
     * 
     * @param accountId the account identifier (COBOL PIC 9(11) TRANCAT-ACCT-ID)
     *                  Maximum value: 99,999,999,999 (11 digits)
     * @return list of all transaction category balances for the specified account,
     *         ordered by transaction type code and category code;
     *         returns empty list if no balances found for the account
     * @see TransactionCategoryBalance#getTcatAcctId()
     * @see Account entity for account relationship
     */
    List<TransactionCategoryBalance> findByTcatAcctId(Long accountId);
    
    /**
     * Find all transaction category balances for a specific account and transaction type.
     * 
     * Replaces COBOL partial key search pattern where reports or analysis jobs need
     * to retrieve balances for a specific account and transaction type combination
     * (e.g., all purchase categories, all cash advance categories).
     * 
     * COBOL pattern replaced:
     * <pre>
     * MOVE ACCOUNT-ID TO TRANCAT-ACCT-ID
     * MOVE TRANSACTION-TYPE TO TRANCAT-TYPE-CD
     * MOVE LOW-VALUES TO TRANCAT-CD
     * EXEC CICS STARTBR FILE('TCATBAL') RIDFLD(TRAN-CAT-KEY) GTEQ END-EXEC
     * PERFORM UNTIL EOF
     *   EXEC CICS READNEXT FILE('TCATBAL') INTO(TRAN-CAT-RECORD) END-EXEC
     *   IF TRANCAT-ACCT-ID NOT = ACCOUNT-ID OR
     *      TRANCAT-TYPE-CD NOT = TRANSACTION-TYPE
     *     SET EOF TO TRUE
     *   ELSE
     *     ... process category balance for this type ...
     *   END-IF
     * END-PERFORM
     * EXEC CICS ENDBR FILE('TCATBAL') END-EXEC
     * </pre>
     * 
     * Java replacement:
     * <pre>
     * List&lt;TransactionCategoryBalance&gt; balances = 
     *     repository.findByTcatAcctIdAndTcatTypeCd(accountId, "PU"); // Purchase transactions
     * BigDecimal totalPurchases = balances.stream()
     *     .map(TransactionCategoryBalance::getTcatBal)
     *     .reduce(BigDecimal.ZERO, BigDecimal::add);
     * </pre>
     * 
     * Generated SQL (optimized by JPA):
     * <pre>
     * SELECT * FROM transaction_category_balance 
     * WHERE tcat_acct_id = ? AND tcat_type_cd = ?
     * ORDER BY tcat_cat_cd
     * </pre>
     * 
     * Common transaction type codes:
     * - "PU" = Purchase transactions
     * - "CA" = Cash advance transactions
     * - "PM" = Payment transactions
     * - "FE" = Fee transactions
     * - "IN" = Interest charges
     * 
     * Usage scenarios:
     * - Generate reports showing spending by category within a transaction type
     * - Calculate subtotals for specific transaction types (e.g., total purchases)
     * - Filter category balances for targeted analysis or alerts
     * 
     * @param accountId the account identifier (COBOL PIC 9(11) TRANCAT-ACCT-ID)
     * @param typeCode the transaction type code (COBOL PIC X(02) TRANCAT-TYPE-CD)
     *                 Maximum length: 2 characters
     * @return list of all category balances for the specified account and transaction type,
     *         ordered by category code;
     *         returns empty list if no matching balances found
     * @see TransactionCategoryBalance#getTcatTypeCd()
     * @see TransactionType entity for transaction type reference data
     */
    List<TransactionCategoryBalance> findByTcatAcctIdAndTcatTypeCd(Long accountId, String typeCode);
    
    /**
     * Find all transaction category balances for a specific account and category code.
     * 
     * Replaces COBOL search pattern where reports need to retrieve balances across
     * multiple transaction types for a single category (e.g., all grocery spending
     * including purchases, returns, adjustments).
     * 
     * COBOL pattern replaced:
     * This requires multiple VSAM reads in COBOL since category code is not the
     * leading part of the key. The COBOL program would need to:
     * <pre>
     * MOVE ACCOUNT-ID TO TRANCAT-ACCT-ID
     * MOVE LOW-VALUES TO TRANCAT-TYPE-CD
     * MOVE CATEGORY-CODE TO TRANCAT-CD
     * EXEC CICS STARTBR FILE('TCATBAL') RIDFLD(TRAN-CAT-KEY) GTEQ END-EXEC
     * PERFORM UNTIL EOF
     *   EXEC CICS READNEXT FILE('TCATBAL') INTO(TRAN-CAT-RECORD) END-EXEC
     *   IF TRANCAT-ACCT-ID NOT = ACCOUNT-ID
     *     SET EOF TO TRUE
     *   ELSE
     *     IF TRANCAT-CD = CATEGORY-CODE
     *       ... process this category balance ...
     *     END-IF
     *   END-IF
     * END-PERFORM
     * EXEC CICS ENDBR FILE('TCATBAL') END-EXEC
     * </pre>
     * 
     * Java replacement (more efficient with proper indexing):
     * <pre>
     * List&lt;TransactionCategoryBalance&gt; balances = 
     *     repository.findByTcatAcctIdAndTcatCatCd(accountId, 1000); // Grocery category
     * BigDecimal totalGrocerySpending = balances.stream()
     *     .map(TransactionCategoryBalance::getTcatBal)
     *     .reduce(BigDecimal.ZERO, BigDecimal::add);
     * </pre>
     * 
     * Generated SQL (with proper index on tcat_acct_id + tcat_cat_cd):
     * <pre>
     * SELECT * FROM transaction_category_balance 
     * WHERE tcat_acct_id = ? AND tcat_cat_cd = ?
     * ORDER BY tcat_type_cd
     * </pre>
     * 
     * Common category codes:
     * - 1000 = Groceries
     * - 2000 = Gas/Fuel
     * - 3000 = Dining/Restaurants
     * - 4000 = Travel
     * - 5000 = Entertainment
     * 
     * Usage scenarios:
     * - Generate reports showing all activity for a category across transaction types
     * - Calculate category totals including purchases, returns, and adjustments
     * - Support customer spending analysis by category regardless of transaction type
     * 
     * Performance note:
     * Requires database index on (tcat_acct_id, tcat_cat_cd) for efficient query
     * execution. Without this index, full table scan would be required.
     * 
     * @param accountId the account identifier (COBOL PIC 9(11) TRANCAT-ACCT-ID)
     * @param catCd the transaction category code (COBOL PIC 9(04) TRANCAT-CD)
     *              Range: 0 to 9999 (4 digits)
     * @return list of all category balances for the specified account and category,
     *         ordered by transaction type code;
     *         returns empty list if no matching balances found
     * @see TransactionCategoryBalance#getTcatCatCd()
     * @see TransactionCategory entity for category reference data
     */
    List<TransactionCategoryBalance> findByTcatAcctIdAndTcatCatCd(Long accountId, Integer catCd);
    
    /*
     * Standard JpaRepository CRUD Methods (inherited, no implementation needed):
     * -------------------------------------------------------------------------
     * 
     * All methods below are automatically implemented by Spring Data JPA and replace
     * corresponding COBOL VSAM file operations:
     * 
     * 1. save(TransactionCategoryBalance balance)
     *    Replaces: EXEC CICS WRITE FILE('TCATBAL') FROM(...) (insert)
     *              EXEC CICS REWRITE FILE('TCATBAL') FROM(...) (update)
     *    Returns: The saved entity with any generated/updated fields
     *    Usage: Insert new category balance or update existing balance amount
     * 
     * 2. saveAll(Iterable<TransactionCategoryBalance> balances)
     *    Batch save operation for multiple category balances
     *    More efficient than multiple save() calls in batch processing
     *    Usage: Bulk insert/update during batch job processing (CBTRN03C)
     * 
     * 3. findById(TransactionCategoryBalanceId id)
     *    Replaces: EXEC CICS READ FILE('TCATBAL') RIDFLD(...) INTO(...)
     *    Returns: Optional<TransactionCategoryBalance> (empty if not found)
     *    Usage: Read specific category balance by composite key
     *    Example: findById(new TransactionCategoryBalanceId(accountId, typeCd, catCd))
     * 
     * 4. existsById(TransactionCategoryBalanceId id)
     *    Check if category balance exists without loading full entity
     *    More efficient than findById().isPresent() for existence checks
     *    Usage: Determine whether to insert or update during batch processing
     * 
     * 5. findAll()
     *    Retrieve all transaction category balance records
     *    Returns: List<TransactionCategoryBalance>
     *    Caution: May return large result set - use pagination or custom queries
     * 
     * 6. findAllById(Iterable<TransactionCategoryBalanceId> ids)
     *    Batch read operation for multiple category balances
     *    More efficient than multiple findById() calls
     *    Usage: Load multiple specific balances for comparison or reporting
     * 
     * 7. count()
     *    Count total number of category balance records in database
     *    Returns: Long (total record count)
     *    Usage: Statistics, validation, monitoring
     * 
     * 8. deleteById(TransactionCategoryBalanceId id)
     *    Replaces: EXEC CICS DELETE FILE('TCATBAL') RIDFLD(...)
     *    Usage: Remove specific category balance (rarely used in production)
     * 
     * 9. delete(TransactionCategoryBalance balance)
     *    Delete using entity instance (must have valid composite key)
     *    Usage: Alternative to deleteById when entity is already loaded
     * 
     * 10. deleteAll()
     *     Delete all category balance records
     *     Caution: Destructive operation - use only for testing/reset
     * 
     * 11. deleteAll(Iterable<TransactionCategoryBalance> balances)
     *     Batch delete operation for specific entities
     *     More efficient than multiple delete() calls
     * 
     * 12. deleteAllById(Iterable<TransactionCategoryBalanceId> ids)
     *     Batch delete operation by composite keys
     *     Most efficient for deleting multiple known balances
     * 
     * Transaction Boundaries:
     * All inherited methods participate in Spring @Transactional context.
     * Service layer should annotate methods with @Transactional to ensure
     * proper ACID guarantees equivalent to COBOL EXEC CICS SYNCPOINT.
     * 
     * Example batch processing usage (CBTRN03C equivalent):
     * <pre>
     * @Transactional
     * public void updateCategoryBalances(Long accountId, List<Transaction> dailyTransactions) {
     *     // Load existing balances for the account
     *     List<TransactionCategoryBalance> balances = repository.findByTcatAcctId(accountId);
     *     Map<TransactionCategoryBalanceId, TransactionCategoryBalance> balanceMap = 
     *         balances.stream().collect(Collectors.toMap(
     *             b -> new TransactionCategoryBalanceId(b.getTcatAcctId(), b.getTcatTypeCd(), b.getTcatCatCd()),
     *             b -> b
     *         ));
     *     
     *     // Update balances based on daily transactions
     *     for (Transaction txn : dailyTransactions) {
     *         TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
     *             accountId, txn.getTransTypeCd(), txn.getTransCatCd()
     *         );
     *         
     *         TransactionCategoryBalance balance = balanceMap.get(key);
     *         if (balance == null) {
     *             // Create new category balance
     *             balance = TransactionCategoryBalance.builder()
     *                 .tcatAcctId(accountId)
     *                 .tcatTypeCd(txn.getTransTypeCd())
     *                 .tcatCatCd(txn.getTransCatCd())
     *                 .tcatBal(BigDecimal.ZERO)
     *                 .build();
     *             balanceMap.put(key, balance);
     *         }
     *         
     *         // Update balance (COBOL PIC S9(09)V99 COMP-3 arithmetic preserved)
     *         BigDecimal currentBal = balance.getTcatBal();
     *         BigDecimal newBal = currentBal.add(txn.getTransAmt());
     *         balance.setTcatBal(newBal);
     *     }
     *     
     *     // Save all updated balances
     *     repository.saveAll(balanceMap.values());
     *     // Transaction commits here (replaces EXEC CICS SYNCPOINT)
     * }
     * </pre>
     */
}
