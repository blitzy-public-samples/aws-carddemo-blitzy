/*
 * InterestCalculationService.java — Batch Interest Calculation Service
 *
 * Faithfully translates CBACT04C.cbl (653 lines) — the batch interest
 * calculation program that iterates TCATBAL records grouped by account ID,
 * retrieves interest rates from DISCGRP (with DEFAULT fallback), computes
 * monthly interest, generates interest transaction records, and updates
 * account balances.
 *
 * COBOL Program: CBACT04C (app/cbl/CBACT04C.cbl)
 * COBOL Copybooks Referenced:
 *   - CVTRA01Y.cpy  (TRAN-CAT-BAL-RECORD — 50 bytes, CategoryBalance entity)
 *   - CVACT01Y.cpy  (ACCOUNT-RECORD — 300 bytes, Account entity)
 *   - CVACT03Y.cpy  (CARD-XREF-RECORD — 50 bytes, CardXref entity)
 *   - CVTRA02Y.cpy  (DIS-GROUP-RECORD — 50 bytes, DiscountGroup entity)
 *   - CVTRA05Y.cpy  (TRAN-RECORD — 350 bytes, Transaction entity)
 *
 * 100% Paragraph-to-Method Traceability:
 *   PROCEDURE DIVISION         → calculateInterest(String)
 *   0000-0400 file opens       → N/A (JPA manages connections)
 *   1000-TCATBALF-GET-NEXT     → Iterator within calculateInterest main loop
 *   1050-UPDATE-ACCOUNT        → updateAccount(Account, BigDecimal)
 *   1100-GET-ACCT-DATA         → getAccountData(String)
 *   1110-GET-XREF-DATA         → getXrefData(String)
 *   1200-GET-INTEREST-RATE     → getInterestRate(String, String, String)
 *   1200-A-GET-DEFAULT-INT-RATE→ getDefaultInterestRate(String, String)
 *   1300-COMPUTE-INTEREST      → computeInterest(BigDecimal, BigDecimal)
 *   1300-B-WRITE-TX            → writeInterestTransaction(String, int, BigDecimal, String, String)
 *   1400-COMPUTE-FEES          → computeFees()
 *   9000-9400 file closes      → N/A (JPA manages connections)
 *   Z-GET-DB2-FORMAT-TIMESTAMP → generateDb2Timestamp()
 *   9910-DISPLAY-IO-STATUS     → log.error() calls
 *   9999-ABEND-PROGRAM         → throw CardDemoException
 *
 * @see com.cardemo.entity.CategoryBalance
 * @see com.cardemo.entity.Account
 * @see com.cardemo.entity.CardXref
 * @see com.cardemo.entity.Transaction
 * @see com.cardemo.entity.DiscountGroup
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DiscountGroup;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.DiscountGroupRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Batch interest calculation service — translates CBACT04C.cbl.
 *
 * <p>Implements the complete COBOL batch interest calculation workflow:</p>
 * <ol>
 *   <li>Read all TCATBAL (category balance) records sorted by account ID</li>
 *   <li>Group records by account — detect account boundary changes</li>
 *   <li>For each account group: load account data and card cross-reference</li>
 *   <li>For each category balance: look up interest rate from DISCGRP
 *       (with DEFAULT fallback when group-specific rate not found)</li>
 *   <li>Compute monthly interest: {@code (catBalance * rate) / 1200}</li>
 *   <li>Generate interest transaction records with DB2-format timestamps</li>
 *   <li>Accumulate interest and update account balance when account changes</li>
 * </ol>
 *
 * <p><strong>Interest Formula (CBACT04C.cbl line 464-465):</strong></p>
 * <pre>
 *   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * </pre>
 * <p>Uses {@link BigDecimal} with {@link RoundingMode#HALF_UP} for exact
 * decimal arithmetic matching COBOL COMP-3 behavior. No floating-point
 * arithmetic is used anywhere in this service.</p>
 *
 * <p><strong>Thread Safety:</strong> All working-storage variables (WS-LAST-ACCT-NUM,
 * WS-MONTHLY-INT, WS-TOTAL-INT, WS-FIRST-TIME, WS-TRANID-SUFFIX) are method-local
 * in {@link #calculateInterest(String)}, ensuring thread safety.</p>
 */
@Service
public class InterestCalculationService {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationService.class);

    /**
     * Divisor constant for monthly interest calculation.
     * Annual rate / 12 months * 100 (rate is percentage) = 1200.
     * COBOL: {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     */
    private static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200);

    /**
     * Scale for monetary calculations — 2 decimal places matching COBOL
     * {@code PIC S9(09)V99 COMP-3} fields (WS-MONTHLY-INT, WS-TOTAL-INT).
     */
    private static final int MONETARY_SCALE = 2;

    /**
     * Interest transaction type code — COBOL: {@code MOVE '01' TO TRAN-TYPE-CD}.
     */
    private static final String INTEREST_TRAN_TYPE_CODE = "01";

    /**
     * Interest transaction category code — COBOL: {@code MOVE '05' TO TRAN-CAT-CD}.
     * Stored as Integer matching the Transaction entity's categoryCode field type.
     */
    private static final int INTEREST_TRAN_CAT_CODE = 5;

    /**
     * Interest transaction source — COBOL: {@code MOVE 'System' TO TRAN-SOURCE}.
     */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /**
     * Interest transaction description prefix —
     * COBOL: {@code STRING 'Int. for a/c ' TRANCAT-ACCT-ID}.
     */
    private static final String INTEREST_DESC_PREFIX = "Int. for a/c ";

    /**
     * Default discount group identifier used as fallback when account-specific
     * group rate not found (DISCGRP-STATUS='23').
     * COBOL: {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Zero-filled merchant ID for interest transactions —
     * COBOL: {@code MOVE 0 TO TRAN-MERCHANT-ID} (PIC 9(09)).
     */
    private static final String ZERO_MERCHANT_ID = "000000000";

    // ========================================================================
    // Injected Repository Dependencies
    // ========================================================================

    private final CategoryBalanceRepository categoryBalanceRepository;
    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;
    private final DiscountGroupRepository discountGroupRepository;

    /**
     * Constructs the InterestCalculationService with all required repository
     * dependencies injected by Spring.
     *
     * @param categoryBalanceRepository repository for TCATBALF VSAM dataset
     * @param accountRepository         repository for ACCTDATA VSAM dataset
     * @param cardXrefRepository        repository for CARDXREF VSAM dataset (AIX)
     * @param transactionRepository     repository for TRANSACT VSAM dataset
     * @param discountGroupRepository   repository for DISCGRP VSAM reference data
     */
    @Autowired
    public InterestCalculationService(
            CategoryBalanceRepository categoryBalanceRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository,
            DiscountGroupRepository discountGroupRepository) {
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
        this.discountGroupRepository = discountGroupRepository;
    }

    // ========================================================================
    // Main Entry Point — PROCEDURE DIVISION USING EXTERNAL-PARMS
    // ========================================================================

    /**
     * Main entry point for the interest calculation batch process.
     *
     * <p>Translates the COBOL PROCEDURE DIVISION USING EXTERNAL-PARMS
     * (CBACT04C.cbl lines 180-240). Iterates all TCATBAL records sorted by
     * accountId, groups them by account, computes interest for each category
     * balance, generates interest transaction records, and updates account
     * balances.</p>
     *
     * <p><strong>COBOL Working-Storage variables mapped to method-locals:</strong></p>
     * <ul>
     *   <li>{@code WS-LAST-ACCT-NUM} → {@code lastAcctNum} (String)</li>
     *   <li>{@code WS-TOTAL-INT} → {@code totalInterest} (BigDecimal)</li>
     *   <li>{@code WS-FIRST-TIME} → {@code firstTime} (boolean)</li>
     *   <li>{@code WS-TRANID-SUFFIX} → {@code tranIdSuffix} (int)</li>
     *   <li>{@code WS-RECORD-COUNT} → {@code recordCount} (int)</li>
     * </ul>
     *
     * <p><strong>Grouping Logic (CBACT04C.cbl lines 194-206):</strong>
     * When TRANCAT-ACCT-ID changes, flush accumulated interest for the prior
     * account (unless first record), reset totals, and load new account and
     * XREF data. After all records are processed, flush the final account.</p>
     *
     * @param parmDate the date parameter in CCYYMMDD format (8 characters),
     *                 from LINKAGE SECTION EXTERNAL-PARMS → PARM-DATE.
     *                 Used as the prefix for generated transaction IDs.
     * @throws CardDemoException if any unrecoverable error occurs during
     *                           processing (account not found, XREF missing,
     *                           DEFAULT discount group missing, etc.)
     */
    @Transactional
    public void calculateInterest(String parmDate) {
        log.info("Interest calculation batch started with PARM-DATE={}", parmDate);

        // ----------------------------------------------------------------
        // Working-Storage variables (method-local for thread safety)
        // Mirrors CBACT04C.cbl WS-MISC-VARS and WS-COUNTERS
        // ----------------------------------------------------------------
        String lastAcctNum = "";                    // WS-LAST-ACCT-NUM PIC X(11)
        BigDecimal totalInterest = BigDecimal.ZERO; // WS-TOTAL-INT PIC S9(09)V99
        boolean firstTime = true;                   // WS-FIRST-TIME PIC X(01) VALUE 'Y'
        int tranIdSuffix = 0;                       // WS-TRANID-SUFFIX PIC 9(08) VALUE 0
        int recordCount = 0;                        // WS-RECORD-COUNT
        Account currentAccount = null;
        String currentCardNum = "";                 // XREF-CARD-NUM for current account

        // ----------------------------------------------------------------
        // Paragraphs 0000-0400: File opens — JPA manages all connections
        // ----------------------------------------------------------------

        // ----------------------------------------------------------------
        // 1000-TCATBALF-GET-NEXT: Read all TCATBAL records sorted by accountId
        // COBOL: READ TCATBAL-FILE sequential, ordered by KSDS primary key
        // JPA: findAll(Sort.by("accountId")) provides equivalent ordering
        // ----------------------------------------------------------------
        List<CategoryBalance> categoryBalances = categoryBalanceRepository.findAll(
                Sort.by("accountId"));

        log.info("Retrieved {} category balance records for processing",
                categoryBalances.size());

        // ----------------------------------------------------------------
        // Main loop: PERFORM UNTIL END-OF-FILE = 'Y'
        // Iterates TCATBAL records with account boundary detection
        // ----------------------------------------------------------------
        for (CategoryBalance catBal : categoryBalances) {
            recordCount++;
            String currentAcctId = catBal.getAccountId();

            // ----------------------------------------------------------
            // Grouping logic (CBACT04C.cbl lines 194-206):
            // IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM
            // ----------------------------------------------------------
            if (!currentAcctId.equals(lastAcctNum)) {
                // IF WS-FIRST-TIME NOT = 'Y' → update prior account
                if (!firstTime) {
                    // 1050-UPDATE-ACCOUNT: flush accumulated interest
                    updateAccount(currentAccount, totalInterest);
                }

                // MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
                lastAcctNum = currentAcctId;

                // MOVE 'N' TO WS-FIRST-TIME
                firstTime = false;

                // MOVE 0 TO WS-TOTAL-INT (reset for new account)
                totalInterest = BigDecimal.ZERO;

                // 1100-GET-ACCT-DATA: load new account
                currentAccount = getAccountData(currentAcctId);

                // 1110-GET-XREF-DATA: load card cross-reference
                currentCardNum = getXrefData(currentAcctId);
            }

            // ----------------------------------------------------------
            // 1200-GET-INTEREST-RATE: look up rate from DISCGRP
            // Uses account's groupId + catBal's typeCode + categoryCode
            // ----------------------------------------------------------
            BigDecimal interestRate = getInterestRate(
                    currentAccount.getGroupId(),
                    catBal.getTypeCode(),
                    String.valueOf(catBal.getCategoryCode()));

            // ----------------------------------------------------------
            // COBOL: IF DIS-INT-RATE NOT = 0
            // Only compute interest when rate is non-zero
            // ----------------------------------------------------------
            if (interestRate.compareTo(BigDecimal.ZERO) != 0) {
                // 1300-COMPUTE-INTEREST
                BigDecimal monthlyInterest = computeInterest(
                        catBal.getBalance(), interestRate);

                // ADD WS-MONTHLY-INT TO WS-TOTAL-INT
                totalInterest = totalInterest.add(monthlyInterest);

                // 1300-B-WRITE-TX: generate interest transaction record
                writeInterestTransaction(parmDate, tranIdSuffix,
                        monthlyInterest, currentAcctId, currentCardNum);
                tranIdSuffix++;
            }

            // 1400-COMPUTE-FEES (stub in COBOL — "To be implemented")
            computeFees();
        }

        // ----------------------------------------------------------------
        // After EOF: flush last account (CBACT04C.cbl line 228-230)
        // IF WS-FIRST-TIME NOT = 'Y' → PERFORM 1050-UPDATE-ACCOUNT
        // ----------------------------------------------------------------
        if (!firstTime && currentAccount != null) {
            updateAccount(currentAccount, totalInterest);
        }

        // ----------------------------------------------------------------
        // Paragraphs 9000-9400: File closes — JPA manages all connections
        // ----------------------------------------------------------------

        log.info("Interest calculation batch completed. " +
                "Records processed: {}, transactions generated: {}",
                recordCount, tranIdSuffix);
    }

    // ========================================================================
    // Paragraph 1050-UPDATE-ACCOUNT
    // ========================================================================

    /**
     * Updates account balance with accumulated interest — paragraph 1050-UPDATE-ACCOUNT.
     *
     * <p>COBOL (CBACT04C.cbl lines 309-325):</p>
     * <pre>
     *   ADD WS-TOTAL-INT TO ACCT-CURR-BAL
     *   MOVE 0 TO ACCT-CURR-CYC-CREDIT
     *   MOVE 0 TO ACCT-CURR-CYC-DEBIT
     *   REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * @param account       the Account entity to update; must not be null
     * @param totalInterest the accumulated interest to add to the account balance
     * @throws CardDemoException if account is null (9999-ABEND-PROGRAM equivalent)
     */
    public void updateAccount(Account account, BigDecimal totalInterest) {
        if (account == null) {
            // 9999-ABEND-PROGRAM: fatal error — null account
            log.error("9999-ABEND-PROGRAM: Cannot update null account in " +
                    "1050-UPDATE-ACCOUNT");
            throw new CardDemoException(
                    "1050-UPDATE-ACCOUNT: Cannot update null account");
        }

        // ADD WS-TOTAL-INT TO ACCT-CURR-BAL
        account.setCurrBal(account.getCurrBal().add(totalInterest));

        // MOVE 0 TO ACCT-CURR-CYC-CREDIT
        account.setCurrCycCredit(BigDecimal.ZERO);

        // MOVE 0 TO ACCT-CURR-CYC-DEBIT
        account.setCurrCycDebit(BigDecimal.ZERO);

        // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        accountRepository.save(account);

        log.debug("1050-UPDATE-ACCOUNT: account={}, interest added={}, " +
                "new balance={}",
                account.getAcctId(), totalInterest, account.getCurrBal());
    }

    // ========================================================================
    // Paragraph 1100-GET-ACCT-DATA
    // ========================================================================

    /**
     * Loads account data by account ID — paragraph 1100-GET-ACCT-DATA.
     *
     * <p>COBOL (CBACT04C.cbl lines 340-365):</p>
     * <pre>
     *   MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID
     *   READ ACCOUNT-FILE INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID
     *   IF ACCTFILE-STATUS NOT= '00'
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     * </pre>
     *
     * @param acctId the 11-character account ID (TRANCAT-ACCT-ID)
     * @return the loaded Account entity
     * @throws CardDemoException if account not found (equivalent to
     *                           ACCTFILE-STATUS != '00' → 9999-ABEND-PROGRAM)
     */
    public Account getAccountData(String acctId) {
        // READ ACCOUNT-FILE INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID
        // If not found (status '23'), create FileStatusException and log code
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    // 9910-DISPLAY-IO-STATUS: log the VSAM file status code
                    FileStatusException fse = new FileStatusException("23",
                            "1100-GET-ACCT-DATA: Account not found for ID="
                                    + acctId);
                    log.error("9910-DISPLAY-IO-STATUS: File status '{}' " +
                            "on ACCTFILE read for account ID={}",
                            fse.getFileStatusCode(), acctId);
                    return fse;
                });
        log.debug("1100-GET-ACCT-DATA: loaded account ID={}", acctId);
        return account;
    }

    // ========================================================================
    // Paragraph 1110-GET-XREF-DATA
    // ========================================================================

    /**
     * Loads card cross-reference data by account ID — paragraph 1110-GET-XREF-DATA.
     *
     * <p>COBOL (CBACT04C.cbl lines 380-405):</p>
     * <pre>
     *   MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID
     *   READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID
     *   IF XREFFILE-STATUS NOT= '00'
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     * </pre>
     *
     * <p>Uses the AIX (Alternate Index) access path: lookup by account ID
     * instead of primary key (card number). Returns the first matching
     * XREF-CARD-NUM for use in generated interest transaction records.</p>
     *
     * @param acctId the account ID for the AIX lookup (FD-XREF-ACCT-ID)
     * @return the card number (XREF-CARD-NUM) from the first matching record
     * @throws CardDemoException if no XREF record found for the account
     *                           (XREFFILE-STATUS != '00' → 9999-ABEND-PROGRAM)
     */
    public String getXrefData(String acctId) {
        // READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID
        List<CardXref> xrefs = cardXrefRepository.findByAccountId(acctId);
        if (xrefs.isEmpty()) {
            // 9910-DISPLAY-IO-STATUS + 9999-ABEND-PROGRAM
            FileStatusException fse = new FileStatusException("23",
                    "1110-GET-XREF-DATA: Card cross-reference not found "
                            + "for account ID=" + acctId);
            log.error("9910-DISPLAY-IO-STATUS: File status '{}' on " +
                    "XREFFILE read for account ID={}",
                    fse.getFileStatusCode(), acctId);
            throw fse;
        }
        // Access CardXref entity fields: getAccountId() + getXrefCardNum()
        CardXref xref = xrefs.getFirst();
        log.debug("1110-GET-XREF-DATA: account={}, card={}",
                xref.getAccountId(), xref.getXrefCardNum());
        return xref.getXrefCardNum();
    }

    // ========================================================================
    // Paragraph 1200-GET-INTEREST-RATE
    // ========================================================================

    /**
     * Retrieves interest rate from discount group — paragraph 1200-GET-INTEREST-RATE.
     *
     * <p>COBOL (CBACT04C.cbl lines 420-445):</p>
     * <pre>
     *   MOVE FD-ACCT-GROUP-ID  TO DIS-ACCT-GROUP-ID
     *   MOVE TRANCAT-TYPE-CD   TO DIS-TRAN-TYPE-CD
     *   MOVE TRANCAT-CD        TO DIS-TRAN-CAT-CD
     *   READ DISCGRP-FILE INTO DIS-GROUP-RECORD KEY IS FD-DISCGRP-KEY
     *   IF DISCGRP-STATUS = '00' OR '23'
     *       IF DISCGRP-STATUS = '23'
     *           PERFORM 1200-A-GET-DEFAULT-INT-RATE
     *       END-IF
     *   ELSE
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     * </pre>
     *
     * <p><strong>Fallback Logic:</strong> When the group-specific rate is not
     * found (DISCGRP-STATUS='23' → Optional.empty()), falls back to the
     * DEFAULT group via {@link #getDefaultInterestRate(String, String)}.</p>
     *
     * @param groupId  account's discount group ID (ACCT-GROUP-ID, up to 10 chars)
     * @param typeCode transaction type code (TRANCAT-TYPE-CD, 2 chars)
     * @param catCode  transaction category code as String (TRANCAT-CD)
     * @return the interest rate (DIS-INT-RATE) as BigDecimal
     * @throws CardDemoException if neither group-specific nor DEFAULT rate found
     */
    public BigDecimal getInterestRate(String groupId, String typeCode,
                                      String catCode) {
        // Convert category code from String to Integer for repository lookup
        Integer catCodeInt = Integer.valueOf(catCode);

        Optional<DiscountGroup> discOpt = discountGroupRepository
                .findByGroupIdAndTranTypeCodeAndTranCatCode(
                        groupId, typeCode, catCodeInt);

        if (discOpt.isPresent()) {
            // DISCGRP-STATUS = '00' → found, use rate
            DiscountGroup disc = discOpt.get();
            BigDecimal rate = disc.getInterestRate();
            log.debug("1200-GET-INTEREST-RATE: group={}, type={}, cat={}, " +
                    "rate={}", disc.getGroupId(), typeCode, catCode, rate);
            return rate;
        }

        // DISCGRP-STATUS = '23' → not found, fallback to DEFAULT group
        log.warn("1200-GET-INTEREST-RATE: Discount group not found for " +
                "group={}, type={}, cat={}. Falling back to DEFAULT.",
                groupId, typeCode, catCode);
        return getDefaultInterestRate(typeCode, catCode);
    }

    // ========================================================================
    // Paragraph 1200-A-GET-DEFAULT-INT-RATE
    // ========================================================================

    /**
     * Retrieves default interest rate — paragraph 1200-A-GET-DEFAULT-INT-RATE.
     *
     * <p>COBOL (CBACT04C.cbl lines 447-460):</p>
     * <pre>
     *   MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     *   READ DISCGRP-FILE INTO DIS-GROUP-RECORD KEY IS FD-DISCGRP-KEY
     *   IF DISCGRP-STATUS NOT= '00'
     *       PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     * </pre>
     *
     * @param typeCode transaction type code (DIS-TRAN-TYPE-CD, 2 chars)
     * @param catCode  transaction category code as String (DIS-TRAN-CAT-CD)
     * @return the default interest rate (DIS-INT-RATE) as BigDecimal
     * @throws CardDemoException if DEFAULT group record not found
     *                           (9999-ABEND-PROGRAM equivalent)
     */
    public BigDecimal getDefaultInterestRate(String typeCode, String catCode) {
        // Convert category code from String to Integer for repository lookup
        Integer catCodeInt = Integer.valueOf(catCode);

        Optional<DiscountGroup> defaultOpt = discountGroupRepository
                .findByGroupIdAndTranTypeCodeAndTranCatCode(
                        DEFAULT_GROUP_ID, typeCode, catCodeInt);

        if (defaultOpt.isEmpty()) {
            // DISCGRP-STATUS NOT= '00' → 9910-DISPLAY-IO-STATUS + 9999-ABEND
            FileStatusException fse = new FileStatusException("23",
                    "1200-A-GET-DEFAULT-INT-RATE: DEFAULT discount group "
                            + "not found for type=" + typeCode
                            + ", cat=" + catCode);
            log.error("9910-DISPLAY-IO-STATUS: File status '{}' on " +
                    "DISCGRP read for DEFAULT group, type={}, cat={}",
                    fse.getFileStatusCode(), typeCode, catCode);
            throw fse;
        }

        DiscountGroup defaultDisc = defaultOpt.get();
        BigDecimal rate = defaultDisc.getInterestRate();
        log.debug("1200-A-GET-DEFAULT-INT-RATE: group={}, type={}, cat={}, " +
                "DEFAULT rate={}", defaultDisc.getGroupId(), typeCode,
                catCode, rate);
        return rate;
    }

    // ========================================================================
    // Paragraph 1300-COMPUTE-INTEREST
    // ========================================================================

    /**
     * Computes monthly interest — paragraph 1300-COMPUTE-INTEREST.
     *
     * <p><strong>EXACT COBOL formula (CBACT04C.cbl line 464-465):</strong></p>
     * <pre>
     *   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     *
     * <p>Uses {@link BigDecimal} with {@link RoundingMode#HALF_UP} matching
     * COBOL default rounding behavior for COMPUTE statements with decimal
     * division. Scale of 2 matches {@code PIC S9(09)V99 COMP-3}.</p>
     *
     * @param catBalance   the category balance (TRAN-CAT-BAL) as BigDecimal
     * @param interestRate the annual interest rate (DIS-INT-RATE) as BigDecimal
     * @return the computed monthly interest (WS-MONTHLY-INT) as BigDecimal
     */
    public BigDecimal computeInterest(BigDecimal catBalance,
                                      BigDecimal interestRate) {
        // COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        BigDecimal monthlyInterest = catBalance
                .multiply(interestRate)
                .divide(MONTHLY_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_UP);

        log.debug("1300-COMPUTE-INTEREST: ({} * {}) / 1200 = {}",
                catBalance, interestRate, monthlyInterest);
        return monthlyInterest;
    }

    // ========================================================================
    // Paragraph 1300-B-WRITE-TX
    // ========================================================================

    /**
     * Writes an interest transaction record — paragraph 1300-B-WRITE-TX.
     *
     * <p>COBOL (CBACT04C.cbl lines 480-520):</p>
     * <pre>
     *   STRING PARM-DATE WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
     *   MOVE '01'        TO TRAN-TYPE-CD
     *   MOVE '05'        TO TRAN-CAT-CD
     *   MOVE 'System'    TO TRAN-SOURCE
     *   STRING 'Int. for a/c ' TRANCAT-ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
     *   MOVE WS-MONTHLY-INT TO TRAN-AMT
     *   MOVE 0            TO TRAN-MERCHANT-ID
     *   MOVE SPACES       TO TRAN-MERCHANT-NAME
     *   MOVE SPACES       TO TRAN-MERCHANT-CITY
     *   MOVE SPACES       TO TRAN-MERCHANT-ZIP
     *   MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
     *   PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
     *   MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS
     *   MOVE DB2-FORMAT-TS TO TRAN-PROC-TS
     *   ADD 1 TO WS-TRANID-SUFFIX
     *   WRITE FD-TRANFILE-REC FROM TRAN-RECORD
     * </pre>
     *
     * <p><strong>Transaction ID format:</strong> PARM-DATE (8 chars) +
     * WS-TRANID-SUFFIX (8 digits, zero-padded) = 16 chars total,
     * matching TRAN-ID PIC X(16).</p>
     *
     * @param parmDate        the date parameter (8 chars, CCYYMMDD format)
     * @param tranIdSuffix    the sequential suffix for transaction ID generation
     *                        (WS-TRANID-SUFFIX PIC 9(08))
     * @param monthlyInterest the computed monthly interest amount (WS-MONTHLY-INT)
     * @param acctId          the account ID for the description field
     * @param cardNum         the card number from XREF lookup (XREF-CARD-NUM)
     */
    public void writeInterestTransaction(String parmDate, int tranIdSuffix,
                                         BigDecimal monthlyInterest,
                                         String acctId, String cardNum) {

        // STRING PARM-DATE WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
        // PARM-DATE PIC X(08) + WS-TRANID-SUFFIX PIC 9(08) = 16 chars
        String tranId = String.format("%s%08d", parmDate, tranIdSuffix);

        // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
        // MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS and TRAN-PROC-TS
        String db2Timestamp = generateDb2Timestamp();

        // Build the interest transaction record using all-fields constructor
        // (Transaction no-arg constructor is protected / JPA-only).
        // Equivalent to the sequential MOVE statements in COBOL:
        //   TRAN-ID, TRAN-TYPE-CD='01', TRAN-CAT-CD='05', TRAN-SOURCE='System',
        //   TRAN-DESC, TRAN-AMT, TRAN-MERCHANT-ID=0, TRAN-MERCHANT-NAME/CITY/ZIP=SPACES,
        //   TRAN-CARD-NUM=XREF-CARD-NUM, TRAN-ORIG-TS, TRAN-PROC-TS
        Transaction tran = new Transaction(
                tranId,                              // TRAN-ID PIC X(16)
                INTEREST_TRAN_TYPE_CODE,             // TRAN-TYPE-CD = '01'
                INTEREST_TRAN_CAT_CODE,              // TRAN-CAT-CD = 5 (PIC 9(04))
                INTEREST_TRAN_SOURCE,                // TRAN-SOURCE = 'System'
                INTEREST_DESC_PREFIX + acctId,       // TRAN-DESC = 'Int. for a/c ' + ACCT-ID
                monthlyInterest,                     // TRAN-AMT = WS-MONTHLY-INT
                ZERO_MERCHANT_ID,                    // TRAN-MERCHANT-ID = '000000000'
                "",                                  // TRAN-MERCHANT-NAME = SPACES
                "",                                  // TRAN-MERCHANT-CITY = SPACES
                "",                                  // TRAN-MERCHANT-ZIP = SPACES
                cardNum,                             // TRAN-CARD-NUM = XREF-CARD-NUM
                db2Timestamp,                        // TRAN-ORIG-TS
                db2Timestamp                         // TRAN-PROC-TS
        );

        // Note: ADD 1 TO WS-TRANID-SUFFIX occurs in calling code (calculateInterest)
        // WRITE FD-TRANFILE-REC FROM TRAN-RECORD
        transactionRepository.save(tran);

        log.debug("1300-B-WRITE-TX: wrote interest transaction ID={}, " +
                "acct={}, amount={}", tranId, acctId, monthlyInterest);
    }

    // ========================================================================
    // Paragraph 1400-COMPUTE-FEES
    // ========================================================================

    /**
     * Computes fees — paragraph 1400-COMPUTE-FEES.
     *
     * <p>This paragraph is documented as "To be implemented" in the original
     * COBOL source (CBACT04C.cbl line 533). Per the AAP migration rule
     * "No feature expansion — strictly parity with CBACT04C.cbl", this method
     * preserves the COBOL stub semantics exactly. The method body is
     * intentionally empty to match the COBOL program's behavior.</p>
     *
     * <p><strong>COBOL (CBACT04C.cbl line 531-533):</strong></p>
     * <pre>
     *   1400-COMPUTE-FEES.
     *       CONTINUE.
     *   *   To be implemented
     * </pre>
     */
    public void computeFees() {
        // COBOL paragraph 1400-COMPUTE-FEES is a documented stub:
        // "To be implemented" — CBACT04C.cbl line 533
        // Per AAP: No feature expansion — strictly parity with CBACT04C.cbl
        // This method intentionally has no implementation to match COBOL behavior.
    }

    // ========================================================================
    // Paragraph Z-GET-DB2-FORMAT-TIMESTAMP
    // ========================================================================

    /**
     * Generates a DB2-format timestamp — paragraph Z-GET-DB2-FORMAT-TIMESTAMP.
     *
     * <p>COBOL (CBACT04C.cbl lines 560-595):</p>
     * <pre>
     *   MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     *   ... extract YYYY, MM, DD, HH, MM, SS, MS ...
     *   STRING WS-CURDATE-YEAR '-' WS-CURDATE-MONTH '-' WS-CURDATE-DAY
     *          '-' WS-CURTIME-HH '.' WS-CURTIME-MM '.' WS-CURTIME-SS
     *          '.' WS-CURTIME-MS '0000'
     *          DELIMITED BY SIZE INTO DB2-FORMAT-TS
     * </pre>
     *
     * <p>Output format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters)
     * matching the AAP-specified ISO-8601 extended timestamp format.</p>
     *
     * @return the current timestamp in DB2 format (26 characters),
     *         e.g., {@code "2026-03-18-14.30.45.123456"}
     */
    public String generateDb2Timestamp() {
        // Z-GET-DB2-FORMAT-TIMESTAMP: delegates to DateConversionUtil static method
        // DateConversionUtil.getCurrentTimestamp() returns YYYY-MM-DD-HH.MM.SS.mmmmmm
        return DateConversionUtil.getCurrentTimestamp();
    }
}
