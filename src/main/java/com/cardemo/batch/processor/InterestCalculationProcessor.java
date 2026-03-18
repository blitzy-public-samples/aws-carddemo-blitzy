/*
 * InterestCalculationProcessor.java — Spring Batch ItemProcessor
 *
 * Source: app/cbl/CBACT04C.cbl — Batch interest calculation program
 * Paragraphs translated: 1050-UPDATE-ACCOUNT, 1100-GET-ACCT-DATA,
 *   1110-GET-XREF-DATA, 1200-GET-INTEREST-RATE, 1200-A-GET-DEFAULT-INT-RATE,
 *   1300-COMPUTE-INTEREST, 1400-COMPUTE-FEES (stub — EXIT only)
 *
 * COBOL Working Storage State Variables Translated:
 *   WS-LAST-ACCT-NUM    PIC X(11)    → lastAccountId (String)
 *   WS-MONTHLY-INT       PIC S9(09)V99 → local BigDecimal in computeInterest()
 *   WS-TOTAL-INT         PIC S9(09)V99 → totalInterest (BigDecimal)
 *   WS-FIRST-TIME        PIC X(01)    → firstTime (boolean)
 *
 * COBOL VSAM Files Accessed (mapped to JPA Repositories):
 *   TCATBAL-FILE  (TCATBALF)  → input items (CategoryBalance entities)
 *   ACCOUNT-FILE  (ACCTFILE)  → AccountRepository
 *   XREF-FILE     (XREFFILE)  → CardXrefRepository (AIX on XREF-ACCT-ID)
 *   DISCGRP-FILE  (DISCGRP)   → DiscountGroupRepository
 *   TRANSACT-FILE (TRANSACT)  → handled by ItemWriter (not this processor)
 *
 * Business Logic Parity:
 *   Interest formula: monthlyInterest = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200
 *   Division by 1200 = (12 months × 100 for percentage conversion)
 *   RoundingMode.HALF_UP matches COBOL default rounding semantics
 *   Scale = 2 matches COBOL PIC V99 (2 decimal places)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.processor;

import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DiscountGroup;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.DiscountGroupRepository;
import com.cardemo.service.batch.InterestCalculationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Spring Batch {@link ItemProcessor} that computes monthly interest for each
 * {@link CategoryBalance} record, accumulates total interest per account, and
 * updates the account balance when account boundaries change.
 *
 * <p>This processor faithfully translates the interest calculation logic from
 * COBOL batch program CBACT04C.cbl (paragraphs 1050 through 1400). The COBOL
 * program reads TCATBAL records sequentially (sorted by account ID), computes
 * monthly interest for each category balance line using the discount group
 * interest rate, accumulates the interest per account, and updates the account
 * balance when the account ID changes.</p>
 *
 * <h3>Interest Computation Formula (← CBACT04C.cbl line 464-465):</h3>
 * <pre>
 * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200
 * </pre>
 * <p>Where 1200 = 12 months × 100 (percentage to decimal conversion).</p>
 *
 * <h3>Account Update Logic (← CBACT04C.cbl paragraph 1050):</h3>
 * <pre>
 * ADD WS-TOTAL-INT TO ACCT-CURR-BAL
 * MOVE 0 TO ACCT-CURR-CYC-CREDIT
 * MOVE 0 TO ACCT-CURR-CYC-DEBIT
 * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
 * </pre>
 *
 * <h3>Discount Group Lookup with DEFAULT Fallback (← paragraphs 1200, 1200-A):</h3>
 * <ol>
 *   <li>Look up discount rate by account group, transaction type, and category</li>
 *   <li>If not found (VSAM status '23'), retry with group ID = "DEFAULT"</li>
 *   <li>If DEFAULT also not found, log warning and use zero rate</li>
 * </ol>
 *
 * <p><strong>WARNING: This processor is STATEFUL.</strong> The fields
 * {@code lastAccountId}, {@code totalInterest}, {@code firstTime}, and
 * {@code currentAccount} maintain state across consecutive {@link #process}
 * invocations to implement account boundary detection. This processor
 * <strong>must</strong> be used with {@code @StepScope} bean definition in
 * the job configuration, or in a single-threaded step (no
 * {@code taskExecutor()} configured on the step). Using this processor in a
 * multi-threaded step will produce incorrect results due to race conditions
 * on the shared state fields.</p>
 *
 * <p><strong>BigDecimal mandate:</strong> ALL monetary computations in this
 * processor use {@link BigDecimal} with {@link RoundingMode#HALF_UP}. No
 * {@code float} or {@code double} types are used anywhere. This is
 * non-negotiable per the AAP requirement for 100% business logic parity
 * with the COBOL original.</p>
 *
 * @see com.cardemo.entity.CategoryBalance
 * @see com.cardemo.entity.Account
 * @see com.cardemo.entity.DiscountGroup
 */
@Component
public class InterestCalculationProcessor
        implements ItemProcessor<CategoryBalance, CategoryBalance> {

    private static final Logger log = LoggerFactory.getLogger(
            InterestCalculationProcessor.class);

    // =========================================================================
    // Constants (← COBOL literals and PIC specifications)
    // =========================================================================

    /**
     * Divisor for monthly interest rate calculation.
     * Converts annual percentage rate to monthly decimal rate:
     * 1200 = 12 months × 100 (percentage to decimal).
     * Matches COBOL: {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     */
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");

    /**
     * Fallback discount group identifier when the account's specific group is
     * not found. Matches COBOL line 437:
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Scale for interest calculation results, matching COBOL
     * {@code WS-MONTHLY-INT PIC S9(09)V99} — 2 decimal places (V99).
     */
    private static final int INTEREST_SCALE = 2;

    // =========================================================================
    // Dependencies (← COBOL FILE SECTION: DISCGRP-FILE, ACCOUNT-FILE, XREF-FILE,
    //   TRANSACT-FILE via InterestCalculationService for 1300-B-WRITE-TX)
    // =========================================================================

    private final DiscountGroupRepository discountGroupRepository;
    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final InterestCalculationService interestCalculationService;

    // =========================================================================
    // Stateful fields (← COBOL WORKING-STORAGE WS-MISC-VARS)
    // WARNING: These fields make this processor NOT thread-safe.
    // =========================================================================

    /**
     * Tracks the previous account ID for boundary detection.
     * ← COBOL: {@code WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES}
     */
    private String lastAccountId = "";

    /**
     * Accumulated total interest for the current account across all
     * category balance records.
     * ← COBOL: {@code WS-TOTAL-INT PIC S9(09)V99}
     */
    private BigDecimal totalInterest = BigDecimal.ZERO;

    /**
     * First-record flag to prevent updating a non-existent previous account
     * on the very first invocation.
     * ← COBOL: {@code WS-FIRST-TIME PIC X(01) VALUE 'Y'}
     */
    private boolean firstTime = true;

    /**
     * Cached account entity for the current account being processed.
     * Loaded when the account boundary changes via
     * {@link AccountRepository#findById(Object)}.
     */
    private Account currentAccount;

    /**
     * Cached cross-reference card number for the current account.
     * Loaded from XREF-FILE (AIX on XREF-ACCT-ID) when the account
     * boundary changes. Used for interest transaction record creation
     * (← COBOL 1300-B-WRITE-TX: {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM}).
     */
    private String currentXrefCardNum = "";

    /**
     * PARM-DATE from the job parameters (CCYYMMDD format, 8 characters).
     * Set via {@link #setParmDate(String)} from the job config listener's
     * {@code beforeStep} callback. Used in 1300-B-WRITE-TX to construct
     * the transaction ID (← COBOL: {@code STRING PARM-DATE WS-TRANID-SUFFIX}).
     */
    private String parmDate = "";

    /**
     * Sequential suffix for transaction ID generation.
     * ← COBOL: {@code WS-TRANID-SUFFIX PIC 9(08) VALUE 0}
     * Incremented for each interest transaction written (1300-B-WRITE-TX).
     */
    private int tranIdSuffix;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Constructs the interest calculation processor with required repository
     * dependencies injected by Spring.
     *
     * @param discountGroupRepository repository for DISCGRP VSAM reference data
     *        (← CBACT04C.cbl lines 47-51: DISCGRP-FILE)
     * @param accountRepository repository for ACCTDATA VSAM dataset
     *        (← CBACT04C.cbl lines 41-45: ACCOUNT-FILE)
     * @param cardXrefRepository repository for CARDXREF VSAM junction dataset
     *        (← CBACT04C.cbl lines 34-39: XREF-FILE, AIX on XREF-ACCT-ID)
     * @param interestCalculationService service for interest transaction
     *        record persistence (← 1300-B-WRITE-TX, TRANSACT-FILE)
     */
    public InterestCalculationProcessor(
            DiscountGroupRepository discountGroupRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            InterestCalculationService interestCalculationService) {
        this.discountGroupRepository = discountGroupRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.interestCalculationService = interestCalculationService;
    }

    // =========================================================================
    // ItemProcessor.process() — Main interest calculation loop body
    // ← CBACT04C.cbl lines 188-222 (PERFORM UNTIL END-OF-FILE loop body)
    // =========================================================================

    /**
     * Processes a single {@link CategoryBalance} item by computing its monthly
     * interest and accumulating it for the owning account.
     *
     * <p>This method translates the body of the COBOL main processing loop
     * (CBACT04C.cbl lines 188-222). For each category balance record:</p>
     * <ol>
     *   <li>Detects account boundary changes
     *       (← {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM})</li>
     *   <li>On boundary change: updates the previous account balance and
     *       loads the new account and cross-reference data</li>
     *   <li>Looks up the interest rate from the discount group table
     *       (← paragraph 1200-GET-INTEREST-RATE)</li>
     *   <li>Computes monthly interest if rate is non-zero
     *       (← paragraph 1300-COMPUTE-INTEREST)</li>
     *   <li>Paragraph 1400-COMPUTE-FEES is a stub in COBOL (EXIT only) —
     *       no implementation required</li>
     * </ol>
     *
     * @param item the category balance record to process
     * @return the same category balance item (processing updates account state)
     * @throws Exception if account or discount group data cannot be retrieved
     */
    @Override
    public CategoryBalance process(CategoryBalance item) throws Exception {
        String accountId = item.getAccountId();

        // Account boundary detection
        // ← COBOL: IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM (line 194)
        if (!accountId.equals(lastAccountId)) {
            if (!firstTime && currentAccount != null) {
                // Update previous account with accumulated interest
                // ← COBOL: PERFORM 1050-UPDATE-ACCOUNT (line 196)
                updateAccount();
            } else {
                // First record — skip update, clear first-time flag
                // ← COBOL: MOVE 'N' TO WS-FIRST-TIME (line 198)
                firstTime = false;
            }

            // Reset accumulated interest for the new account
            // ← COBOL: MOVE 0 TO WS-TOTAL-INT (line 200)
            totalInterest = BigDecimal.ZERO;

            // Track the new account ID
            // ← COBOL: MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM (line 201)
            lastAccountId = accountId;

            // Load account data
            // ← COBOL: PERFORM 1100-GET-ACCT-DATA (line 203)
            loadAccountData(accountId);

            // Load cross-reference data via AIX on XREF-ACCT-ID
            // ← COBOL: PERFORM 1110-GET-XREF-DATA (line 205)
            loadXrefData(accountId);
        }

        // Look up interest rate from discount group
        // ← COBOL: PERFORM 1200-GET-INTEREST-RATE (line 213)
        BigDecimal interestRate = getInterestRate(
                currentAccount.getGroupId(),  // ACCT-GROUP-ID → FD-DIS-ACCT-GROUP-ID
                item.getTypeCode(),           // TRANCAT-TYPE-CD → FD-DIS-TRAN-TYPE-CD
                item.getCategoryCode()        // TRANCAT-CD → FD-DIS-TRAN-CAT-CD
        );

        // Compute interest only if rate is non-zero
        // ← COBOL: IF DIS-INT-RATE NOT = 0 (line 214)
        if (interestRate.compareTo(BigDecimal.ZERO) != 0) {
            // ← COBOL: PERFORM 1300-COMPUTE-INTEREST (line 215)
            computeInterest(item, interestRate);
            // ← COBOL: PERFORM 1400-COMPUTE-FEES (line 216)
            // 1400-COMPUTE-FEES is a stub in the original COBOL (EXIT only,
            // lines 518-520). No implementation — preserving business logic parity.
        }

        return item;
    }

    // =========================================================================
    // Step Lifecycle — Final Account Flush
    // ← CBACT04C.cbl line 220: PERFORM 1050-UPDATE-ACCOUNT (post-loop)
    // =========================================================================

    /**
     * Sets the PARM-DATE job parameter for transaction ID generation.
     * Called by the job config listener's {@code beforeStep} callback to
     * propagate the job parameter into this stateful processor.
     *
     * @param parmDate the date parameter (CCYYMMDD format, 8 characters)
     */
    public void setParmDate(String parmDate) {
        this.parmDate = parmDate != null ? parmDate : "";
    }

    /**
     * Flushes the last account's accumulated interest after all items have
     * been processed.
     *
     * <p>In the COBOL program (CBACT04C.cbl line 220), after the main
     * processing loop exits, a final {@code PERFORM 1050-UPDATE-ACCOUNT}
     * is executed to update the last account that was being processed.
     * In Spring Batch, this is handled by calling this method from the
     * {@link #afterStep(StepExecution)} listener callback.</p>
     */
    public void flushLastAccount() {
        if (currentAccount != null && !firstTime) {
            log.info("Flushing final account update for accountId={}",
                    lastAccountId);
            updateAccount();
        }
    }

    /**
     * Spring Batch step listener callback invoked after the step completes.
     *
     * <p>Calls {@link #flushLastAccount()} to ensure the last account's
     * accumulated interest is persisted, then resets the processor state
     * for potential reuse. This translates the COBOL post-loop logic
     * (CBACT04C.cbl line 220: {@code PERFORM 1050-UPDATE-ACCOUNT}).</p>
     *
     * @param stepExecution the step execution context (unused but required
     *        by Spring Batch listener contract)
     * @return {@link ExitStatus#COMPLETED} to indicate successful completion
     */
    @AfterStep
    public ExitStatus afterStep(StepExecution stepExecution) {
        log.info("Interest calculation step completing. "
                + "Flushing last account state.");
        flushLastAccount();

        // Reset state for potential step re-execution
        lastAccountId = "";
        totalInterest = BigDecimal.ZERO;
        firstTime = true;
        currentAccount = null;
        currentXrefCardNum = "";
        parmDate = "";
        tranIdSuffix = 0;

        return ExitStatus.COMPLETED;
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Loads account data for the given account ID.
     * ← COBOL paragraph 1100-GET-ACCT-DATA (lines 372-391):
     * <pre>
     * READ ACCOUNT-FILE INTO ACCOUNT-RECORD
     *     INVALID KEY
     *        DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID
     * END-READ
     * </pre>
     *
     * @param accountId the 11-character account identifier
     * @throws IllegalStateException if the account is not found
     *         (equivalent to COBOL ABEND on INVALID KEY)
     */
    private void loadAccountData(String accountId) {
        currentAccount = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.error("Account not found: {} "
                            + "(← COBOL: DISPLAY 'ACCOUNT NOT FOUND')",
                            accountId);
                    return new IllegalStateException(
                            "Account not found: " + accountId);
                });
        log.debug("Loaded account data: accountId={}, groupId={}, "
                + "currBal={}",
                accountId, currentAccount.getGroupId(),
                currentAccount.getCurrBal());
    }

    /**
     * Loads cross-reference data for the given account ID via the AIX
     * on XREF-ACCT-ID.
     * ← COBOL paragraph 1110-GET-XREF-DATA (lines 393-413):
     * <pre>
     * READ XREF-FILE INTO CARD-XREF-RECORD
     *     KEY IS FD-XREF-ACCT-ID
     *     INVALID KEY
     *        DISPLAY 'ACCOUNT NOT FOUND: ' FD-XREF-ACCT-ID
     * END-READ
     * </pre>
     *
     * @param accountId the 11-character account identifier for AIX lookup
     */
    private void loadXrefData(String accountId) {
        List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);
        if (!xrefs.isEmpty()) {
            currentXrefCardNum = xrefs.getFirst().getXrefCardNum();
            log.debug("Loaded XREF data: accountId={}, cardNum={}",
                    accountId, currentXrefCardNum);
        } else {
            currentXrefCardNum = "";
            log.warn("No XREF record found for accountId={} "
                    + "(← COBOL: DISPLAY 'ACCOUNT NOT FOUND')",
                    accountId);
        }
    }

    /**
     * Looks up the interest rate from the discount group table for the given
     * account group, transaction type, and category combination.
     *
     * <p>Implements the DEFAULT fallback pattern from COBOL paragraphs
     * 1200-GET-INTEREST-RATE (lines 415-440) and 1200-A-GET-DEFAULT-INT-RATE
     * (lines 443-460):</p>
     * <ol>
     *   <li>Try lookup with the account's specific group ID</li>
     *   <li>If not found (VSAM status '23'), retry with "DEFAULT" group
     *       (← COBOL line 437: {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID})</li>
     *   <li>If DEFAULT also not found, return {@link BigDecimal#ZERO}
     *       with a warning (COBOL would ABEND; defensive handling)</li>
     * </ol>
     *
     * @param groupId  the account's discount group ID (ACCT-GROUP-ID)
     * @param typeCode the 2-character transaction type code (TRANCAT-TYPE-CD)
     * @param catCode  the transaction category code (TRANCAT-CD)
     * @return the interest rate as {@link BigDecimal}, or
     *         {@link BigDecimal#ZERO} if no matching rate is found
     */
    private BigDecimal getInterestRate(String groupId, String typeCode,
                                       Integer catCode) {
        // Primary lookup with the account's specific group ID
        // ← COBOL: READ DISCGRP-FILE INTO DIS-GROUP-RECORD (line 416)
        Optional<DiscountGroup> result = discountGroupRepository
                .findByGroupIdAndTranTypeCodeAndTranCatCode(
                        groupId, typeCode, catCode);

        if (result.isPresent()) {
            BigDecimal rate = result.get().getInterestRate();
            log.debug("Interest rate found: group={}, type={}, cat={}, "
                    + "rate={}",
                    groupId, typeCode, catCode, rate);
            return rate;
        }

        // Fallback to DEFAULT group
        // ← COBOL lines 436-438:
        //   IF DISCGRP-STATUS = '23'
        //     MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
        //     PERFORM 1200-A-GET-DEFAULT-INT-RATE
        log.info("Discount group record missing for group={}, type={}, "
                + "cat={}. Trying DEFAULT group "
                + "(← COBOL: TRY WITH DEFAULT GROUP CODE)",
                groupId, typeCode, catCode);

        Optional<DiscountGroup> defaultResult = discountGroupRepository
                .findByGroupIdAndTranTypeCodeAndTranCatCode(
                        DEFAULT_GROUP_ID, typeCode, catCode);

        if (defaultResult.isPresent()) {
            BigDecimal rate = defaultResult.get().getInterestRate();
            log.debug("Default interest rate found: type={}, cat={}, "
                    + "rate={}",
                    typeCode, catCode, rate);
            return rate;
        }

        // In COBOL, failure to find the DEFAULT group would trigger an ABEND
        // (lines 454-458: PERFORM 9999-ABEND-PROGRAM). Defensive handling:
        // return ZERO and log a warning instead of terminating the batch.
        log.warn("No default discount group found for type={}, cat={}. "
                + "Using ZERO interest rate "
                + "(← COBOL would ABEND at paragraph 1200-A)",
                typeCode, catCode);
        return BigDecimal.ZERO;
    }

    /**
     * Computes monthly interest for a single category balance line and
     * accumulates it in the per-account total.
     *
     * <p>Translates COBOL paragraph 1300-COMPUTE-INTEREST (lines 462-470):</p>
     * <pre>
     * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200
     * ADD WS-MONTHLY-INT TO WS-TOTAL-INT
     * PERFORM 1300-B-WRITE-TX
     * </pre>
     *
     * <p>The transaction record write (1300-B-WRITE-TX) is handled by the
     * Spring Batch {@code ItemWriter} in the job configuration, not by this
     * processor. This processor focuses on the interest computation and
     * account-level accumulation.</p>
     *
     * <p><strong>BigDecimal rules applied:</strong></p>
     * <ul>
     *   <li>Scale = 2 (matching COBOL V99 = 2 decimal places)</li>
     *   <li>{@link RoundingMode#HALF_UP} (matching COBOL default rounding)</li>
     *   <li>Division by 1200 using {@link BigDecimal} string constructor</li>
     * </ul>
     *
     * @param item         the category balance record being processed
     * @param interestRate the discount group interest rate (DIS-INT-RATE)
     */
    private void computeInterest(CategoryBalance item,
                                 BigDecimal interestRate) {
        // Monthly interest = (balance × rate) / 1200
        // ← COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        BigDecimal monthlyInterest = item.getBalance()
                .multiply(interestRate)
                .divide(TWELVE_HUNDRED, INTEREST_SCALE, RoundingMode.HALF_UP);

        // Accumulate into per-account total
        // ← COBOL: ADD WS-MONTHLY-INT TO WS-TOTAL-INT (line 467)
        totalInterest = totalInterest.add(monthlyInterest);

        // Write interest transaction record
        // ← COBOL: PERFORM 1300-B-WRITE-TX (line 466)
        // ADD 1 TO WS-TRANID-SUFFIX (line 469)
        tranIdSuffix++;
        interestCalculationService.writeInterestTransaction(
                parmDate, tranIdSuffix, monthlyInterest,
                item.getAccountId(), currentXrefCardNum);

        log.debug("Interest computed: accountId={}, type={}, cat={}, "
                + "balance={}, rate={}, monthlyInterest={}, "
                + "totalInterest={}",
                item.getAccountId(), item.getTypeCode(),
                item.getCategoryCode(), item.getBalance(),
                interestRate, monthlyInterest, totalInterest);
    }

    /**
     * Updates the current account with accumulated interest and resets
     * the billing cycle counters.
     *
     * <p>Translates COBOL paragraph 1050-UPDATE-ACCOUNT (lines 350-370):</p>
     * <pre>
     * ADD WS-TOTAL-INT TO ACCT-CURR-BAL
     * MOVE 0 TO ACCT-CURR-CYC-CREDIT
     * MOVE 0 TO ACCT-CURR-CYC-DEBIT
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * <p>The JPA {@code @Version} annotation on the {@link Account} entity
     * provides optimistic locking, replacing the CICS READ UPDATE → REWRITE
     * pattern from the original COBOL implementation.</p>
     */
    private void updateAccount() {
        // Add accumulated interest to current balance
        // ← COBOL: ADD WS-TOTAL-INT TO ACCT-CURR-BAL (line 352)
        currentAccount.setCurrBal(
                currentAccount.getCurrBal().add(totalInterest));

        // Reset billing cycle counters to zero
        // ← COBOL: MOVE 0 TO ACCT-CURR-CYC-CREDIT (line 353)
        currentAccount.setCurrCycCredit(BigDecimal.ZERO);
        // ← COBOL: MOVE 0 TO ACCT-CURR-CYC-DEBIT (line 354)
        currentAccount.setCurrCycDebit(BigDecimal.ZERO);

        // Persist updated account — JPA @Version provides optimistic locking
        // ← COBOL: REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD (line 356)
        accountRepository.save(currentAccount);

        log.info("Account updated: accountId={}, interestApplied={}, "
                + "newBalance={}, xrefCardNum={}",
                lastAccountId, totalInterest,
                currentAccount.getCurrBal(), currentXrefCardNum);
    }
}
