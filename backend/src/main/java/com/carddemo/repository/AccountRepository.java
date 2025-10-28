package com.carddemo.repository;

import com.carddemo.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository interface for Account entity operations.
 * 
 * Converted from COBOL VSAM file operations on ACCTFILE dataset.
 * Original copybook: CVACT01Y.cpy (ACCOUNT-RECORD, 300-byte record length)
 * 
 * This repository replaces all COBOL EXEC CICS commands for account file I/O:
 * - EXEC CICS READ FILE('ACCTFILE') → findById(Long accountId)
 * - EXEC CICS WRITE FILE('ACCTFILE') → save(Account account) [for insert]
 * - EXEC CICS REWRITE FILE('ACCTFILE') → save(Account account) [for update]
 * - EXEC CICS DELETE FILE('ACCTFILE') → deleteById(Long accountId)
 * 
 * VSAM to PostgreSQL Migration Notes:
 * 
 * 1. Key Structure:
 *    - VSAM KSDS primary key: ACCT-ID (PIC 9(11))
 *    - PostgreSQL primary key: acct_id (BIGINT)
 *    - Both use 11-digit numeric account identifier
 * 
 * 2. Index Replication:
 *    - VSAM alternate index on ACCT-ACTIVE-STATUS → idx_account_status (B-tree)
 *    - VSAM alternate index on ACCT-GROUP-ID → idx_account_group (B-tree)
 *    - PostgreSQL B-tree indexes maintain sub-10ms key access performance equivalent to VSAM
 * 
 * 3. Data Type Conversions:
 *    - COBOL PIC 9(11) → Java Long (primary key)
 *    - COBOL PIC S9(10)V99 COMP-3 → Java BigDecimal(12,2) for financial precision
 *    - COBOL PIC X(10) dates → Java LocalDate
 *    - COBOL PIC X(1) status → Java String(1)
 * 
 * 4. Transaction Boundaries:
 *    - COBOL EXEC CICS SYNCPOINT → Spring @Transactional on service layer methods
 *    - JPA optimistic locking (version field) replicates VSAM RBA locking semantics
 *    - Automatic rollback on exception maintains CICS unit-of-work integrity
 * 
 * 5. Error Handling:
 *    - COBOL file-status '00' (success) → Successful JPA operation
 *    - COBOL file-status '23' (record not found) → Optional.empty() or EntityNotFoundException
 *    - COBOL file-status '22' (duplicate key) → DataIntegrityViolationException
 *    - COBOL RESP codes → Spring DataAccessException hierarchy
 * 
 * 6. Performance Characteristics:
 *    - VSAM KSDS read performance: sub-10ms for primary key access
 *    - PostgreSQL performance target: sub-10ms for findById() with B-tree index
 *    - Connection pooling (HikariCP) maintains high throughput for 10,000 TPS requirement
 *    - Batch operations use JPA batch inserts/updates for efficiency
 * 
 * Referenced COBOL Programs:
 * - COACTUPC.cbl: Account update and maintenance (WRITE, REWRITE, DELETE operations)
 * - COACTVWC.cbl: Account view and inquiry (READ operations)
 * - CBACT01C.cbl: Account file list and validation batch job (sequential READ)
 * - CBACT02C.cbl: Account interest calculation batch job (READ, REWRITE)
 * - CBACT03C.cbl: Account credit limit processing batch job (READ, REWRITE)
 * - CBACT04C.cbl: Account expiration processing batch job (READ, REWRITE)
 * 
 * Database Schema:
 * Table: account
 * Primary Key: acct_id (BIGINT)
 * Indexes:
 *   - PRIMARY KEY (acct_id) - automatic B-tree index
 *   - idx_account_status ON acct_active_status - for status filtering
 *   - idx_account_group ON acct_group_id - for group-based queries
 * 
 * Foreign Key References:
 * - card.card_acct_id → account.acct_id (one account has many cards)
 * - card_account_xref.xref_acct_id → account.acct_id (cross-reference table)
 * - transaction_category_balance.tcat_acct_id → account.acct_id (category balances)
 * 
 * Usage Examples:
 * 
 * // Replace COBOL: EXEC CICS READ FILE('ACCTFILE') INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID)
 * Optional<Account> account = accountRepository.findById(accountId);
 * 
 * // Replace COBOL: EXEC CICS WRITE FILE('ACCTFILE') FROM(ACCOUNT-RECORD)
 * Account newAccount = Account.builder()
 *     .acctId(accountId)
 *     .acctActiveStatus("Y")
 *     .acctCurrBal(BigDecimal.ZERO)
 *     .acctCreditLimit(new BigDecimal("5000.00"))
 *     .build();
 * accountRepository.save(newAccount);
 * 
 * // Replace COBOL: EXEC CICS REWRITE FILE('ACCTFILE') FROM(ACCOUNT-RECORD)
 * account.setAcctCurrBal(newBalance);
 * accountRepository.save(account);
 * 
 * // Replace COBOL: EXEC CICS DELETE FILE('ACCTFILE') RIDFLD(ACCT-ID)
 * accountRepository.deleteById(accountId);
 * 
 * // Replace COBOL: Browse by status filter
 * List<Account> activeAccounts = accountRepository.findByAcctActiveStatus("Y");
 * 
 * // Replace COBOL: Browse by group ID
 * List<Account> groupAccounts = accountRepository.findByAcctGroupId("PREMIUM");
 * 
 * @see Account
 * @see com.carddemo.service.AccountService
 * @see com.carddemo.controller.AccountController
 * 
 * @version 1.0
 * @since 2024
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Find all accounts by active status.
     * 
     * Replaces COBOL sequential file browse with status filter:
     * EXEC CICS STARTBR FILE('ACCTFILE') ...
     * EXEC CICS READNEXT ... (filtering on ACCT-ACTIVE-STATUS)
     * 
     * Uses PostgreSQL index idx_account_status for efficient retrieval.
     * 
     * Valid status values:
     * - 'Y' = Active account (can process transactions)
     * - 'N' = Inactive account (cannot process transactions)
     * - 'C' = Closed account (permanently closed)
     * - 'S' = Suspended account (temporarily suspended)
     * 
     * Used by:
     * - Account listing screens to filter active accounts
     * - Batch processing jobs to select accounts for processing
     * - Transaction authorization to validate account status
     * 
     * @param status Account active status indicator ('Y', 'N', 'C', or 'S')
     * @return List of accounts matching the status, empty list if none found
     */
    List<Account> findByAcctActiveStatus(String status);

    /**
     * Find all accounts by group ID.
     * 
     * Replaces COBOL sequential file browse with group filter:
     * EXEC CICS STARTBR FILE('ACCTFILE') ...
     * EXEC CICS READNEXT ... (filtering on ACCT-GROUP-ID)
     * 
     * Uses PostgreSQL index idx_account_group for efficient retrieval.
     * 
     * Group ID categorizes accounts for:
     * - Product type grouping (e.g., 'STANDARD', 'PREMIUM', 'PLATINUM')
     * - Market segment grouping (e.g., 'RETAIL', 'BUSINESS')
     * - Organizational unit grouping (e.g., regional offices)
     * - Batch processing job grouping (process accounts by group)
     * 
     * Used by:
     * - Account reports grouped by product type or market segment
     * - Batch processing jobs that operate on account groups
     * - Interest calculation jobs applying different rates by group
     * - Fee assessment jobs applying group-specific fee structures
     * 
     * @param groupId Account group identifier (max 10 characters)
     * @return List of accounts in the group, empty list if none found
     */
    List<Account> findByAcctGroupId(String groupId);
}
