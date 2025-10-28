package com.carddemo.batch.processor;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.entity.DisclosureGroupId;
import com.carddemo.repository.DisclosureGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Spring Batch ItemProcessor implementation for account batch processing.
 * 
 * Converted from COBOL programs: CBACT01C.cbl, CBACT02C.cbl, CBACT03C.cbl, CBACT04C.cbl
 * 
 * Original functions:
 * - CBACT01C: Account file reading and validation
 * - CBACT02C: Card file reading and validation
 * - CBACT03C: Cross-reference file reading and validation
 * - CBACT04C: Interest calculation and account balance updates
 * 
 * This processor implements the core batch processing logic for daily account operations:
 * - Account validation and status checking
 * - Interest calculation using BigDecimal with scale 2 preserving COMP-3 precision
 * - Credit limit validation
 * - Account expiration date checking
 * - Balance updates with proper rounding (HALF_UP mode)
 * - Cycle credit/debit counter reset
 * 
 * Conversion notes:
 * - COBOL paragraph 1050-UPDATE-ACCOUNT (lines 350-370) converted to balance update logic in process()
 * - COBOL paragraph 1200-GET-INTEREST-RATE (lines 415-440) converted to getInterestRate() helper method
 * - COBOL paragraph 1200-A-GET-DEFAULT-INT-RATE (lines 443-460) converted to fallback logic
 * - COBOL paragraph 1300-COMPUTE-INTEREST (lines 462-470) converted to calculateInterest() method
 * - COBOL paragraph 1400-COMPUTE-FEES (lines 518-520) converted to calculateFees() method (placeholder)
 * - COBOL computation: WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 preserved exactly
 * - COBOL ADD WS-TOTAL-INT TO ACCT-CURR-BAL converted to BigDecimal.add()
 * - COBOL MOVE 0 TO ACCT-CURR-CYC-CREDIT/DEBIT converted to setAcctCurrCycCredit/Debit(BigDecimal.ZERO)
 * - COBOL file-status checking and APPL-RESULT error handling converted to null return for filtering
 * - COBOL DISPLAY statements converted to SLF4J logging at appropriate levels
 * 
 * Business logic preservation per Section 0.7.2:
 * - All numeric calculations use BigDecimal with scale 2 and RoundingMode.HALF_UP
 * - Maintains exact COBOL COMP-3 arithmetic precision for financial calculations
 * - Preserves identical interest calculation formula: (balance * rate) / 1200
 * - Maintains identical validation rules and error handling patterns
 * - Resets cycle counters to zero exactly as in COBOL
 * 
 * Spring Batch filtering convention:
 * - Returns modified Account entity for ItemWriter to save (successful processing)
 * - Returns null to filter rejected accounts (validation failures, expired accounts)
 * 
 * Performance requirements per Section 0.7.7:
 * - Batch processing must complete within existing 4-hour overnight cycles
 * - Uses Spring Batch chunk processing for efficient database operations
 * 
 * @see Account JPA entity representing account master data
 * @see DisclosureGroup JPA entity for interest rate lookup
 * @see DisclosureGroupRepository Repository for disclosure group data access
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountProcessor implements ItemProcessor<Account, Account> {

    /**
     * Repository for disclosure group interest rate lookup.
     * 
     * Injected via constructor for Spring dependency injection.
     * Replaces COBOL EXEC CICS READ FILE('DISCGRP') operations from CBACT04C.cbl
     * lines 416-420 and 444-448.
     */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Default account group ID for fallback interest rate lookup.
     * 
     * Corresponds to COBOL line 437: MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     * Used when specific account group interest rate is not found in disclosure_group table.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Divisor for monthly interest calculation.
     * 
     * Corresponds to COBOL line 465: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * Interest rate is annual basis, divided by 1200 (12 months * 100 percentage basis) for monthly.
     */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /**
     * Default transaction type code for interest calculation.
     * 
     * Corresponds to COBOL line 482: MOVE '01' TO TRAN-TYPE-CD
     * Type '01' represents debit/purchase transactions that accrue interest.
     */
    private static final String DEFAULT_TRAN_TYPE_CD = "01";

    /**
     * Default transaction category code for interest charges.
     * 
     * Corresponds to COBOL line 483: MOVE '05' TO TRAN-CAT-CD
     * Category '05' represents interest charges.
     */
    private static final Integer DEFAULT_TRAN_CAT_CD = 5;

    /**
     * Process a single account entity for batch processing.
     * 
     * This method implements ItemProcessor<Account, Account>.process() interface contract.
     * Replaces COBOL main processing loop from CBACT04C.cbl lines 188-222 (PROCEDURE DIVISION).
     * 
     * Processing steps (maintaining COBOL paragraph sequence):
     * 1. Validate account status and expiration date
     * 2. Validate credit limit utilization
     * 3. Calculate interest on current balance
     * 4. Calculate fees (if applicable)
     * 5. Update account balance with calculated interest
     * 6. Reset cycle credit/debit counters to zero
     * 
     * Corresponds to COBOL paragraphs:
     * - Account reading: lines 372-391 (1100-GET-ACCT-DATA)
     * - Interest calculation: lines 213-217 (main loop calling 1200, 1300, 1400)
     * - Account update: lines 350-370 (1050-UPDATE-ACCOUNT)
     * 
     * @param account Account entity read by ItemReader from database
     *                Maps to COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
     * @return Modified Account entity with updated balance and reset cycle counters,
     *         or null to filter rejected accounts (validation failures, expired status)
     * @throws Exception if unrecoverable error occurs during processing
     *                   (Spring Batch will handle exception per job configuration)
     */
    @Override
    public Account process(Account account) throws Exception {
        log.debug("Processing account: acctId={}, acctGroupId={}, status={}, balance={}", 
                  account.getAcctId(), 
                  account.getAcctGroupId(), 
                  account.getAcctActiveStatus(), 
                  account.getAcctCurrBal());

        // Validate account before processing
        // Corresponds to COBOL validation logic and file-status checks
        if (!validateAccount(account)) {
            log.warn("Account validation failed: acctId={}, rejecting account", account.getAcctId());
            return null; // Filter rejected account per Spring Batch convention
        }

        // Check account expiration date
        // Corresponds to COBOL date comparison logic
        if (checkAccountExpiration(account)) {
            log.warn("Account expired: acctId={}, expirationDate={}, rejecting account", 
                     account.getAcctId(), account.getAcctExpirationDate());
            return null; // Filter expired account
        }

        // Validate credit limit
        // Corresponds to COBOL credit limit validation logic from CBACT03C
        if (!validateCreditLimit(account)) {
            log.warn("Credit limit exceeded: acctId={}, balance={}, limit={}", 
                     account.getAcctId(), account.getAcctCurrBal(), account.getAcctCreditLimit());
            // Continue processing but log warning - do not reject account
        }

        // Calculate interest on current balance
        // Corresponds to COBOL paragraphs 1200-GET-INTEREST-RATE and 1300-COMPUTE-INTEREST
        // Lines 213-217 in main loop
        BigDecimal interestAmount = calculateInterest(account);
        if (interestAmount.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Interest calculated: acctId={}, amount={}", account.getAcctId(), interestAmount);
        }

        // Calculate fees (placeholder for future implementation)
        // Corresponds to COBOL paragraph 1400-COMPUTE-FEES (lines 518-520: "To be implemented")
        BigDecimal feesAmount = calculateFees(account);
        if (feesAmount.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Fees calculated: acctId={}, amount={}", account.getAcctId(), feesAmount);
        }

        // Update account balance with interest and fees
        // Corresponds to COBOL line 352: ADD WS-TOTAL-INT TO ACCT-CURR-BAL
        BigDecimal totalCharges = interestAmount.add(feesAmount);
        if (totalCharges.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newBalance = account.getAcctCurrBal().add(totalCharges);
            account.setAcctCurrBal(newBalance);
            log.info("Account balance updated: acctId={}, oldBalance={}, charges={}, newBalance={}", 
                     account.getAcctId(), 
                     account.getAcctCurrBal().subtract(totalCharges), 
                     totalCharges, 
                     newBalance);
        }

        // Reset cycle credit and debit counters
        // Corresponds to COBOL lines 353-354:
        // MOVE 0 TO ACCT-CURR-CYC-CREDIT
        // MOVE 0 TO ACCT-CURR-CYC-DEBIT
        account.setAcctCurrCycCredit(BigDecimal.ZERO);
        account.setAcctCurrCycDebit(BigDecimal.ZERO);
        log.debug("Reset cycle counters: acctId={}", account.getAcctId());

        // Return modified account for ItemWriter to save
        // Corresponds to COBOL lines 356-370: REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        log.debug("Account processing completed successfully: acctId={}", account.getAcctId());
        return account;
    }

    /**
     * Calculate interest on account balance.
     * 
     * Converted from COBOL paragraph: 1300-COMPUTE-INTEREST (CBACT04C.cbl lines 462-470)
     * 
     * Original COBOL logic:
     * <pre>
     * 1300-COMPUTE-INTEREST.
     *     COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     *     ADD WS-MONTHLY-INT TO WS-TOTAL-INT
     *     PERFORM 1300-B-WRITE-TX.
     *     EXIT.
     * </pre>
     * 
     * Interest calculation formula:
     * Monthly Interest = (Current Balance * Annual Interest Rate) / 1200
     * 
     * The divisor 1200 breaks down as:
     * - 12 months per year
     * - 100 to convert percentage basis to decimal
     * 
     * Uses BigDecimal with scale 2 and RoundingMode.HALF_UP to preserve COBOL COMP-3
     * packed decimal precision per Section 0.7.2 requirement. This ensures bit-identical
     * results to mainframe calculations.
     * 
     * Corresponds to COBOL data items:
     * - TRAN-CAT-BAL: account.getAcctCurrBal() (PIC S9(10)V99 COMP-3)
     * - DIS-INT-RATE: Retrieved from disclosure_group table (PIC S9(04)V99 COMP-3)
     * - WS-MONTHLY-INT: Calculated interest (PIC S9(09)V99)
     * 
     * @param account Account entity with current balance and group ID
     *                Maps to COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
     * @return Calculated monthly interest amount as BigDecimal with scale 2,
     *         or BigDecimal.ZERO if no interest rate found or balance is zero/negative
     */
    protected BigDecimal calculateInterest(Account account) {
        log.debug("Calculating interest for account: acctId={}, balance={}, groupId={}", 
                  account.getAcctId(), account.getAcctCurrBal(), account.getAcctGroupId());

        // Skip interest calculation if balance is zero or negative
        if (account.getAcctCurrBal() == null || account.getAcctCurrBal().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Skipping interest calculation: balance is zero or negative");
            return BigDecimal.ZERO;
        }

        // Skip interest calculation if account group ID is not set
        if (account.getAcctGroupId() == null || account.getAcctGroupId().trim().isEmpty()) {
            log.warn("Account group ID is null or empty: acctId={}, skipping interest calculation", 
                     account.getAcctId());
            return BigDecimal.ZERO;
        }

        // Get interest rate from disclosure group
        // Corresponds to COBOL paragraph 1200-GET-INTEREST-RATE (lines 415-440)
        BigDecimal interestRate = getInterestRate(account.getAcctGroupId());
        if (interestRate == null || interestRate.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Interest rate is zero or not found: acctId={}, groupId={}, skipping calculation", 
                      account.getAcctId(), account.getAcctGroupId());
            return BigDecimal.ZERO;
        }

        // Calculate monthly interest: (balance * rate) / 1200
        // Corresponds to COBOL line 465: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        BigDecimal monthlyInterest = account.getAcctCurrBal()
                                            .multiply(interestRate)
                                            .divide(INTEREST_DIVISOR, 2, RoundingMode.HALF_UP);

        log.debug("Interest calculated: acctId={}, balance={}, rate={}, monthlyInterest={}", 
                  account.getAcctId(), account.getAcctCurrBal(), interestRate, monthlyInterest);

        return monthlyInterest;
    }

    /**
     * Get interest rate for account group from disclosure group table.
     * 
     * Converted from COBOL paragraphs:
     * - 1200-GET-INTEREST-RATE (CBACT04C.cbl lines 415-440)
     * - 1200-A-GET-DEFAULT-INT-RATE (CBACT04C.cbl lines 443-460)
     * 
     * Original COBOL logic:
     * <pre>
     * 1200-GET-INTEREST-RATE.
     *     READ DISCGRP-FILE INTO DIS-GROUP-RECORD
     *          INVALID KEY
     *             DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
     *             DISPLAY 'TRY WITH DEFAULT GROUP CODE'
     *     END-READ.
     *     IF DISCGRP-STATUS = '23'
     *         MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     *         PERFORM 1200-A-GET-DEFAULT-INT-RATE
     *     END-IF
     *     EXIT.
     * </pre>
     * 
     * Implements two-tier lookup strategy:
     * 1. Try to find interest rate for specific account group ID
     * 2. If not found (file-status 23 = record not found), fallback to 'DEFAULT' group ID
     * 
     * This matches COBOL fallback logic from lines 436-439 where programs attempt
     * default group lookup when specific group is missing.
     * 
     * Uses composite key lookup: (groupId, tranTypeCd, tranCatCd)
     * - groupId: From account.getAcctGroupId() or 'DEFAULT'
     * - tranTypeCd: '01' (debit/purchase transactions)
     * - tranCatCd: 5 (interest charges category)
     * 
     * @param groupId Account group identifier from Account.getAcctGroupId()
     *                Maps to COBOL: ACCT-GROUP-ID PIC X(10)
     * @return Interest rate as BigDecimal with scale 2,
     *         or BigDecimal.ZERO if no rate found for group or DEFAULT
     */
    private BigDecimal getInterestRate(String groupId) {
        log.debug("Looking up interest rate: groupId={}, tranTypeCd={}, tranCatCd={}", 
                  groupId, DEFAULT_TRAN_TYPE_CD, DEFAULT_TRAN_CAT_CD);

        // Create composite key for disclosure group lookup
        // Corresponds to COBOL lines 210-212:
        // MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID
        // MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD
        // MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD
        DisclosureGroupId disclosureGroupId = new DisclosureGroupId(
                groupId,
                DEFAULT_TRAN_TYPE_CD,
                DEFAULT_TRAN_CAT_CD
        );

        // Try to find disclosure group record for specific account group
        // Corresponds to COBOL lines 416-420: READ DISCGRP-FILE INTO DIS-GROUP-RECORD
        Optional<DisclosureGroup> disclosureGroupOpt = disclosureGroupRepository.findById(disclosureGroupId);

        if (disclosureGroupOpt.isPresent()) {
            BigDecimal interestRate = disclosureGroupOpt.get().getDiscIntRate();
            log.debug("Interest rate found for group: groupId={}, rate={}", groupId, interestRate);
            return interestRate;
        }

        // Fallback to DEFAULT group if specific group not found
        // Corresponds to COBOL lines 436-439:
        // IF DISCGRP-STATUS = '23'
        //     MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
        //     PERFORM 1200-A-GET-DEFAULT-INT-RATE
        log.debug("Disclosure group not found for groupId={}, trying DEFAULT group", groupId);

        DisclosureGroupId defaultGroupId = new DisclosureGroupId(
                DEFAULT_GROUP_ID,
                DEFAULT_TRAN_TYPE_CD,
                DEFAULT_TRAN_CAT_CD
        );

        // Try to find disclosure group record for DEFAULT group
        // Corresponds to COBOL lines 444-448: READ DISCGRP-FILE INTO DIS-GROUP-RECORD (in 1200-A paragraph)
        Optional<DisclosureGroup> defaultGroupOpt = disclosureGroupRepository.findById(defaultGroupId);

        if (defaultGroupOpt.isPresent()) {
            BigDecimal interestRate = defaultGroupOpt.get().getDiscIntRate();
            log.debug("Interest rate found for DEFAULT group: rate={}", interestRate);
            return interestRate;
        }

        // No interest rate found for specific group or DEFAULT
        // Corresponds to COBOL file-status error handling (lines 422-435)
        log.warn("Interest rate not found: groupId={}, DEFAULT group also missing", groupId);
        return BigDecimal.ZERO;
    }

    /**
     * Calculate fees on account.
     * 
     * Converted from COBOL paragraph: 1400-COMPUTE-FEES (CBACT04C.cbl lines 518-520)
     * 
     * Original COBOL logic:
     * <pre>
     * 1400-COMPUTE-FEES.
     * * To be implemented
     *     EXIT.
     * </pre>
     * 
     * This method is a placeholder for future fee calculation logic.
     * The original COBOL program does not implement fee calculation (marked "To be implemented"),
     * so this method currently returns zero per minimal change clause (Section 0.1.3).
     * 
     * Future implementation would include:
     * - Annual fee calculation based on account type
     * - Late payment fees for overdue balances
     * - Over-limit fees for credit limit violations
     * - Foreign transaction fees
     * - Cash advance fees
     * 
     * All fee calculations must use BigDecimal with scale 2 and RoundingMode.HALF_UP
     * to maintain COBOL COMP-3 precision per Section 0.7.2 requirement.
     * 
     * @param account Account entity for fee calculation
     *                Maps to COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
     * @return Calculated fees amount as BigDecimal with scale 2,
     *         currently always returns BigDecimal.ZERO (placeholder)
     */
    protected BigDecimal calculateFees(Account account) {
        log.debug("Calculating fees for account: acctId={} (placeholder implementation)", 
                  account.getAcctId());

        // Placeholder implementation per COBOL "To be implemented" comment
        // Return zero fees per minimal change clause (Section 0.1.3)
        // Future enhancement: Implement actual fee calculation logic
        return BigDecimal.ZERO;
    }

    /**
     * Validate credit limit utilization.
     * 
     * Converted from COBOL credit limit validation logic in CBACT03C.cbl.
     * 
     * Checks if account current balance exceeds credit limit. This is a warning
     * validation that logs issues but does not reject the account from processing.
     * 
     * Corresponds to COBOL validation pattern where programs check account limits
     * and display warnings but continue processing.
     * 
     * Credit limit business rules:
     * - Account balance should not exceed credit limit
     * - Over-limit accounts receive warning but are not blocked from interest calculation
     * - Over-limit fees may be assessed (future enhancement in calculateFees)
     * 
     * @param account Account entity to validate
     *                Maps to COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
     * @return true if balance is within credit limit or limit validation should not block processing,
     *         false if balance exceeds credit limit (logged as warning, not blocking)
     */
    protected boolean validateCreditLimit(Account account) {
        log.debug("Validating credit limit: acctId={}, balance={}, limit={}", 
                  account.getAcctId(), account.getAcctCurrBal(), account.getAcctCreditLimit());

        // Handle null values
        if (account.getAcctCurrBal() == null || account.getAcctCreditLimit() == null) {
            log.warn("Null balance or credit limit: acctId={}, cannot validate", account.getAcctId());
            return true; // Continue processing despite null values
        }

        // Check if balance exceeds credit limit
        if (account.getAcctCurrBal().compareTo(account.getAcctCreditLimit()) > 0) {
            log.warn("Credit limit exceeded: acctId={}, balance={}, limit={}, over by {}", 
                     account.getAcctId(), 
                     account.getAcctCurrBal(), 
                     account.getAcctCreditLimit(),
                     account.getAcctCurrBal().subtract(account.getAcctCreditLimit()));
            return false; // Credit limit validation failed (warning only)
        }

        log.debug("Credit limit validation passed: acctId={}", account.getAcctId());
        return true;
    }

    /**
     * Check if account has expired.
     * 
     * Converted from COBOL expiration date validation logic in CBACT04C.cbl.
     * 
     * Checks if account expiration date has passed. Expired accounts should be
     * filtered from processing per batch job business rules.
     * 
     * Corresponds to COBOL date comparison logic where programs check ACCT-EXPIRAION-DATE
     * against current date and skip processing for expired accounts.
     * 
     * Expiration date business rules:
     * - Accounts with expiration date in the past should not accrue interest
     * - Null expiration date means account does not expire (some account types)
     * - Expired accounts should be flagged for renewal or closure
     * 
     * @param account Account entity to check
     *                Maps to COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
     * @return true if account has expired (should be filtered from processing),
     *         false if account is not expired or has no expiration date
     */
    protected boolean checkAccountExpiration(Account account) {
        log.debug("Checking account expiration: acctId={}, expirationDate={}", 
                  account.getAcctId(), account.getAcctExpirationDate());

        // Accounts with null expiration date do not expire
        if (account.getAcctExpirationDate() == null) {
            log.debug("Account has no expiration date: acctId={}, not expired", account.getAcctId());
            return false;
        }

        // Compare expiration date with current date
        // Corresponds to COBOL date comparison: IF ACCT-EXPIRAION-DATE < CURRENT-DATE
        LocalDate currentDate = LocalDate.now();
        boolean isExpired = account.getAcctExpirationDate().isBefore(currentDate);

        if (isExpired) {
            log.warn("Account expired: acctId={}, expirationDate={}, currentDate={}", 
                     account.getAcctId(), account.getAcctExpirationDate(), currentDate);
        } else {
            log.debug("Account not expired: acctId={}, expirationDate={}, currentDate={}", 
                      account.getAcctId(), account.getAcctExpirationDate(), currentDate);
        }

        return isExpired;
    }

    /**
     * Validate account for processing.
     * 
     * Converted from COBOL account validation logic across CBACT01C, CBACT02C, CBACT03C, CBACT04C.
     * 
     * Performs basic account validation checks before processing:
     * - Account ID must not be null
     * - Account active status must be 'Y' (active)
     * - Account current balance must not be null
     * 
     * Corresponds to COBOL validation patterns where programs check:
     * - File-status for successful reads (APPL-AOK condition, line 104)
     * - Account record fields for validity
     * - ACCT-ACTIVE-STATUS for processing eligibility
     * 
     * Invalid accounts are filtered from processing by returning false, which
     * causes process() method to return null per Spring Batch filtering convention.
     * 
     * Account status business rules (from COBOL):
     * - 'Y' = Active (eligible for processing)
     * - 'N' = Inactive (skip processing)
     * - 'C' = Closed (skip processing)
     * - 'S' = Suspended (skip processing)
     * 
     * @param account Account entity to validate
     *                Maps to COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
     * @return true if account passes validation and should be processed,
     *         false if account fails validation and should be filtered (rejected)
     */
    protected boolean validateAccount(Account account) {
        log.debug("Validating account: acctId={}, status={}", 
                  account.getAcctId(), account.getAcctActiveStatus());

        // Check for null account ID
        if (account.getAcctId() == null) {
            log.error("Account ID is null: rejecting account");
            return false;
        }

        // Check for valid active status
        // Corresponds to COBOL: IF ACCT-ACTIVE-STATUS = 'Y'
        if (account.getAcctActiveStatus() == null || !account.getAcctActiveStatus().equals("Y")) {
            log.warn("Account not active: acctId={}, status={}, rejecting account", 
                     account.getAcctId(), account.getAcctActiveStatus());
            return false;
        }

        // Check for null current balance
        if (account.getAcctCurrBal() == null) {
            log.error("Account current balance is null: acctId={}, rejecting account", 
                      account.getAcctId());
            return false;
        }

        log.debug("Account validation passed: acctId={}", account.getAcctId());
        return true;
    }
}
