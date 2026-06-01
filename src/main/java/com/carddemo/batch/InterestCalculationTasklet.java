package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.exception.DiscloseGroupNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.BigDecimalUtil;
import com.carddemo.util.DateConversionUtil;
import com.carddemo.util.TransactionIdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Spring Batch {@link Tasklet} that performs the monthly interest run &mdash; the Java port of the
 * COBOL batch program {@code app/cbl/CBACT04C.cbl} (CardDemo_v1.0-15-g27d6c6f-68), the INTCALC step
 * of the critical batch sequence {@code POSTTRAN -> INTCALC -> COMBTRAN -> CREASTMT}.
 *
 * <p><strong>Origin &mdash; what this replaces.</strong> {@code CBACT04C} walks the
 * transaction-category-balance file ({@code TCATBAL}, keyed {@code account + type + category}) front
 * to back. Because the file is ordered by that key, every record for one account is contiguous, so
 * the program accumulates a per-account interest total ({@code WS-TOTAL-INT}) across that account's
 * category rows. For each row it looks up the disclosure-group interest rate
 * ({@code 1200-GET-INTEREST-RATE}, with a {@code 'DEFAULT'} fallback), and &mdash; only when the
 * rate is non-zero &mdash; computes the monthly interest ({@code 1300-COMPUTE-INTEREST}) and writes
 * an interest transaction ({@code 1300-B-WRITE-TX}). When the account number changes (and once more
 * at end-of-file) it applies the accumulated interest to the account and zeroes the cycle buckets
 * ({@code 1050-UPDATE-ACCOUNT}).</p>
 *
 * <p><strong>What this tasklet does.</strong> It reproduces that orchestration against the relational
 * model. Rather than relying on physical file ordering, it reads all
 * {@link TransactionCategoryBalance} rows ({@code findAll()}) and groups them by account into a
 * {@link TreeMap} so accounts are processed in ascending {@code account_id} order &mdash; a
 * deterministic equivalent of the COBOL key sequence (important for stable interest-transaction-ID
 * assignment and reproducible parity output). For each account it then performs the exact COBOL
 * per-row logic and a single end-of-account update. The result is value-for-value identical to
 * {@code CBACT04C} because the program's only cross-row state is the per-account accumulator, which
 * is reset on every account boundary.</p>
 *
 * <h2>Preserved business rules</h2>
 * <ul>
 *   <li><strong>PR-01 (interest formula, preserved line-by-line).</strong> {@code 1300-COMPUTE-INTEREST}
 *       [L462-L470] {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} becomes
 *       {@code tranCatBal.multiply(rate).divide(BigDecimalUtil.INTEREST_DIVISOR, 2, RoundingMode.HALF_UP)}.
 *       The direct {@code multiply().divide()} chain is used deliberately (not
 *       {@code BigDecimalUtil.scaledMultiply}) so the intermediate product keeps full precision
 *       before the single scale-2 {@code HALF_UP} division &mdash; matching the COBOL {@code COMPUTE}
 *       semantics and the {@code InterestCalculationParityTest} oracle.</li>
 *   <li><strong>PR-02 (DEFAULT fallback).</strong> {@code 1200-GET-INTEREST-RATE} /
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} [L415-L460]: look up {@code (groupId, type, category)};
 *       on a miss (COBOL file status {@code '23'}) retry with {@code groupId = "DEFAULT"}; if the
 *       DEFAULT lookup also misses, abend &mdash; here a {@link DiscloseGroupNotFoundException}
 *       (mirrors {@code 9999-ABEND-PROGRAM}). See {@link #lookupInterestRate(String, String, String)}.</li>
 *   <li><strong>Rate gate</strong> [L214] {@code IF DIS-INT-RATE NOT = 0}: interest is computed and a
 *       transaction is emitted only when the resolved rate is non-zero. The gate is on the
 *       <em>rate</em>, not on the computed monthly interest &mdash; a zero balance against a non-zero
 *       rate still emits a (zero-amount) transaction, exactly as the COBOL does.</li>
 *   <li><strong>PR-08 (account REWRITE).</strong> {@code 1050-UPDATE-ACCOUNT} [L350-L370]
 *       {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}, {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT /
 *       ACCT-CURR-CYC-DEBIT}, {@code REWRITE} is delegated to
 *       {@link AccountBalanceUpdater#applyInterestAndCloseCycle(Account, BigDecimal)} and is invoked
 *       <em>once per account regardless of total</em> so the cycle buckets are always zeroed for the
 *       new cycle.</li>
 *   <li><strong>PR-10 (transaction ID format).</strong> 16 characters = {@code parmDate}(10) +
 *       sequential suffix(6), generated by {@link TransactionIdGenerator#nextBatchId(String)}; the
 *       per-execution counter is reset at the start of {@link #execute} via
 *       {@link TransactionIdGenerator#resetCounter()}.</li>
 *   <li><strong>PR-11 (DB2 timestamp).</strong> {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are set
 *       from a single DB2-format instant ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}); the entity stores
 *       {@code LocalDateTime}, so the value is round-tripped through
 *       {@link DateConversionUtil#fromDb2Timestamp(String)} of
 *       {@link DateConversionUtil#nowAsDb2Timestamp()} to truncate to the COBOL centisecond precision.
 *       Both fields receive the same value, matching the COBOL {@code MOVE DB2-FORMAT-TS} to both.</li>
 *   <li><strong>PR-16 (BigDecimal scale 2 + HALF_UP).</strong> All monetary arithmetic uses
 *       {@link BigDecimal} at scale 2 with {@link RoundingMode#HALF_UP}; no {@code float}/{@code double}.</li>
 *   <li><strong>PR-23 (lock ordering).</strong> Per account the access order is ACCOUNT (read) -&gt;
 *       DISCGRP (read) -&gt; TRANSACTION (write) -&gt; ACCOUNT (update), matching the documented VSAM
 *       convention and avoiding deadlocks.</li>
 *   <li><strong>PR-24 (unit of work).</strong> {@link #execute} is annotated
 *       {@code @Transactional(propagation = REQUIRES_NEW)}, reproducing the implicit CICS
 *       {@code SYNCPOINT} / batch-step transaction boundary. {@link AccountBalanceUpdater} declares
 *       {@code Propagation.MANDATORY} and therefore joins this transaction, as do the
 *       {@link TransactionRepository#save(Object)} writes.</li>
 *   <li><strong>PR-29 (constructor injection).</strong> All collaborators are {@code final} and
 *       injected via the Lombok {@code @RequiredArgsConstructor}; no field injection.</li>
 * </ul>
 *
 * <p>{@code 1400-COMPUTE-FEES} [L518-L520] is an empty {@code "To be implemented"} stub in the COBOL
 * source and therefore contributes no behavior to port.</p>
 *
 * @see AccountBalanceUpdater
 * @see TransactionIdGenerator
 * @see com.carddemo.repository.DisclosureGroupRepository
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterestCalculationTasklet implements Tasklet {

    /**
     * Name of the batch job parameter carrying the 10-character run date, equivalent to the COBOL
     * {@code PARM-DATE PIC X(10)} supplied via {@code INTCALC.jcl} {@code PARM='2022071800'}. It is
     * the prefix of every generated interest transaction ID (PR-10).
     */
    private static final String PARAM_TRAN_DATE = "tranDate";

    /** Exact required length of {@link #PARAM_TRAN_DATE}, matching COBOL {@code PARM-DATE PIC X(10)}. */
    private static final int TRAN_DATE_LENGTH = 10;

    /**
     * Disclosure-group fallback identifier (PR-02). Matches the {@code 'DEFAULT'} literal moved to
     * {@code FD-DIS-ACCT-GROUP-ID} in {@code CBACT04C} {@code 1200-GET-INTEREST-RATE} [L437].
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Transaction type code stamped on every interest transaction. {@code CBACT04C} {@code 1300-B-WRITE-TX}
     * [L482] {@code MOVE '01' TO TRAN-TYPE-CD}; persisted to the {@code CHAR(2)} {@code type_cd} column.
     */
    private static final String INTEREST_TRAN_TYPE = "01";

    /**
     * Transaction category code stamped on every interest transaction. {@code CBACT04C} {@code 1300-B-WRITE-TX}
     * [L483] {@code MOVE '05' TO TRAN-CAT-CD}, where {@code TRAN-CAT-CD} is {@code PIC 9(04)}, so the
     * stored value is the four-character zero-padded {@code "0005"}. This matches the seeded
     * {@code transaction_categories ('01','0005','Interest Amount')} row (Flyway V3) referenced by value
     * and the {@code CHAR(4)} {@code cat_cd} column.
     */
    private static final String INTEREST_TRAN_CAT = "0005";

    /**
     * Transaction source stamped on every interest transaction. {@code CBACT04C} {@code 1300-B-WRITE-TX}
     * [L484] {@code MOVE 'System' TO TRAN-SOURCE}.
     */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /**
     * Description prefix for interest transactions. {@code CBACT04C} {@code 1300-B-WRITE-TX} [L485-L489]
     * {@code STRING 'Int. for a/c ' ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC}; the literal is 13
     * characters including the trailing space and is followed by the 11-digit zero-padded account id
     * (COBOL {@code ACCT-ID PIC 9(11)}).
     */
    private static final String INTEREST_TRAN_DESC_PREFIX = "Int. for a/c ";

    /**
     * Format for the account-id suffix of the transaction description: 11-digit, left-zero-padded,
     * mirroring {@code ACCT-ID PIC 9(11)} rendered by {@code STRING ... DELIMITED BY SIZE}.
     */
    private static final String ACCT_ID_DESC_FORMAT = "%011d";

    /** Merchant id stamped on interest transactions. {@code 1300-B-WRITE-TX} [L491] {@code MOVE 0 TO TRAN-MERCHANT-ID}. */
    private static final long INTEREST_MERCHANT_ID = 0L;

    /**
     * Merchant name/city/zip value for interest transactions. {@code 1300-B-WRITE-TX} [L492-L494]
     * {@code MOVE SPACES TO TRAN-MERCHANT-NAME / -CITY / -ZIP}; an empty string is the relational
     * equivalent of COBOL {@code SPACES} for these nullable {@code VARCHAR} columns.
     */
    private static final String EMPTY_MERCHANT_FIELD = "";

    private final TransactionCategoryBalanceRepository tranCatBalRepository;
    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final TransactionRepository transactionRepository;
    private final AccountBalanceUpdater accountBalanceUpdater;
    private final TransactionIdGenerator transactionIdGenerator;

    /**
     * Executes the entire interest run once per step invocation (single-shot tasklet), porting the
     * {@code CBACT04C} {@code PROCEDURE DIVISION} main loop [app/cbl/CBACT04C.cbl L188-L222].
     *
     * <p>The run date is taken from the {@value #PARAM_TRAN_DATE} job parameter (COBOL
     * {@code PARM='2022071800'}) and validated to be exactly {@value #TRAN_DATE_LENGTH} characters,
     * since it forms the 10-character prefix of every generated transaction ID (PR-10). The
     * per-execution transaction-ID counter is reset so suffixes begin at {@code 000001}. All
     * transaction-category-balance rows are then read and grouped by account (ascending
     * {@code account_id}), and each account is processed by {@link #processAccount}.</p>
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}, PR-24); the delegated account update and
     * all transaction writes join this transaction.</p>
     *
     * @param contribution the step contribution used to report the number of processed accounts
     * @param chunkContext the chunk context providing access to the job parameters
     * @return {@link RepeatStatus#FINISHED} &mdash; the tasklet completes the whole run in one pass
     * @throws IllegalStateException if the {@value #PARAM_TRAN_DATE} parameter is missing or not
     *         exactly {@value #TRAN_DATE_LENGTH} characters
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // === Resolve and validate the run date (COBOL PARM-DATE PIC X(10), INTCALC.jcl PARM=). ===
        // StepContext.getJobParameters() returns Map<String,Object>; render to String defensively so
        // a String-typed parameter (the common case) and any other type both validate by length.
        Object tranDateParam = chunkContext.getStepContext()
                .getJobParameters()
                .get(PARAM_TRAN_DATE);
        String tranDate = tranDateParam != null ? tranDateParam.toString() : null;
        if (tranDate == null || tranDate.length() != TRAN_DATE_LENGTH) {
            throw new IllegalStateException(
                    "InterestCalculationTasklet requires a '" + PARAM_TRAN_DATE + "' job parameter of exactly "
                    + TRAN_DATE_LENGTH + " characters (CBACT04C PARM='2022071800'); got: " + tranDate);
        }
        log.info("InterestCalculationTasklet starting with {}={}", PARAM_TRAN_DATE, tranDate);

        // Restart the per-execution suffix counter so the first emitted ID is parmDate + "000001"
        // (COBOL WS-TRANID-SUFFIX PIC 9(06) VALUE 0, pre-incremented in 1300-B-WRITE-TX).
        transactionIdGenerator.resetCounter();

        // === Read every TCATBAL row and group by account in ascending account_id order. ===
        // The COBOL relies on the physical TRAN-CAT-KEY (account+type+category) ordering of TCATBAL;
        // the TreeMap reproduces that deterministic account sequence over an unordered findAll().
        List<TransactionCategoryBalance> allBalances = tranCatBalRepository.findAll();
        Map<Long, List<TransactionCategoryBalance>> balancesByAccount = groupByAccount(allBalances);
        log.info("Loaded {} TCATBAL row(s) across {} account(s)",
                allBalances.size(), balancesByAccount.size());

        long processedAccounts = 0L;
        for (Map.Entry<Long, List<TransactionCategoryBalance>> entry : balancesByAccount.entrySet()) {
            processAccount(entry.getKey(), entry.getValue(), tranDate);
            processedAccounts++;
        }

        log.info("InterestCalculationTasklet completed \u2014 processedAccounts={}, interestTransactionsEmitted={}",
                processedAccounts, transactionIdGenerator.currentCounter());
        contribution.incrementWriteCount(processedAccounts);
        return RepeatStatus.FINISHED;
    }

    /**
     * Processes a single account's transaction-category-balance rows, porting the per-account body of
     * the {@code CBACT04C} main loop plus the end-of-account update.
     *
     * <p>Steps (lock order ACCOUNT -&gt; DISCGRP -&gt; TRANSACTION -&gt; ACCOUNT, PR-23):</p>
     * <ol>
     *   <li>load the account ({@code 1100-GET-ACCT-DATA}); if it is absent, log and skip &mdash; there
     *       is no account record to update, so nothing (not even cycle-bucket zeroing) is applied;</li>
     *   <li>resolve the card number once via the cross-reference ({@code 1110-GET-XREF-DATA}); the
     *       first card on the account is used for every interest transaction, as in the COBOL which
     *       reads the XREF once per account;</li>
     *   <li>for each category row: resolve the rate with DEFAULT fallback and, when the rate is
     *       non-zero (COBOL {@code IF DIS-INT-RATE NOT = 0}), compute the monthly interest (PR-01),
     *       accumulate it into the per-account total, and emit an interest transaction;</li>
     *   <li>apply the accumulated total to the account and zero the cycle buckets exactly once
     *       ({@code 1050-UPDATE-ACCOUNT}, PR-08) &mdash; performed even when the total is zero so the
     *       cycle credit/debit accumulators are always reset for the new cycle.</li>
     * </ol>
     *
     * @param accountId the account id (the group key, equal to {@code TRANCAT-ACCT-ID} / {@code ACCT-ID})
     * @param balances  the account's transaction-category-balance rows
     * @param parmDate  the validated 10-character run date used as the transaction-ID prefix
     */
    private void processAccount(Long accountId, List<TransactionCategoryBalance> balances, String parmDate) {
        // 1100-GET-ACCT-DATA: load the account master record.
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            // COBOL displays 'ACCOUNT NOT FOUND' for the key; with no managed account row there is
            // nothing to REWRITE, so we skip this account rather than abend (resilient batch behavior).
            log.warn("Skipping account {} \u2014 no matching row in accounts table (TCATBAL references a missing account)",
                    accountId);
            return;
        }

        // ACCT-GROUP-ID drives the disclosure-group lookup; trim the fixed-width value (COBOL PIC X(10)).
        String accountGroupId = account.getGroupId() != null ? account.getGroupId().trim() : "";

        // 1110-GET-XREF-DATA: resolve the card number once per account (first cross-reference card).
        // The cross-reference element type is inferred from CardXrefRepository.findByAccountId so the
        // entity type need not be named/imported here (only the whitelisted repository is depended on).
        String cardNum = cardXrefRepository.findByAccountId(accountId).stream()
                .findFirst()
                .map(xref -> xref.getXrefCardNum())
                .orElse(EMPTY_MERCHANT_FIELD);

        // WS-TOTAL-INT, reset to zero at each account boundary (scale-2 monetary zero, PR-16).
        BigDecimal wsTotalInt = BigDecimalUtil.ZERO;

        for (TransactionCategoryBalance tcb : balances) {
            String typeCd = extractTypeCd(tcb);
            String catCd = extractCategoryCd(tcb);
            // TRAN-CAT-BAL is the interest base; treat a null balance as zero (PR-16).
            BigDecimal tranCatBal = BigDecimalUtil.nullSafe(tcb.getTranCatBal());

            // 1200-GET-INTEREST-RATE (with PR-02 DEFAULT fallback).
            BigDecimal rate = lookupInterestRate(accountGroupId, typeCd, catCd);

            // Rate gate (COBOL L214: IF DIS-INT-RATE NOT = 0). Compute + emit only when rate is non-zero.
            if (rate != null && rate.signum() != 0) {
                // PR-01: monthlyInterest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200, scale 2, HALF_UP.
                // Direct multiply().divide() keeps full intermediate precision before the single
                // scale-2 rounding (do NOT pre-round the product).
                BigDecimal monthlyInterest = tranCatBal
                        .multiply(rate)
                        .divide(BigDecimalUtil.INTEREST_DIVISOR, BigDecimalUtil.SCALE_TWO, RoundingMode.HALF_UP);

                // ADD WS-MONTHLY-INT TO WS-TOTAL-INT.
                wsTotalInt = BigDecimalUtil.scaledAdd(wsTotalInt, monthlyInterest);

                // 1300-B-WRITE-TX: emit the interest transaction (unconditional within the rate gate).
                emitInterestTransaction(accountId, monthlyInterest, parmDate, cardNum);

                log.debug("Interest acct={} type={} cat={} bal={} rate={} monthlyInt={}",
                        accountId, typeCd, catCd, tranCatBal, rate, monthlyInterest);
            }
        }

        // 1050-UPDATE-ACCOUNT (PR-08): ADD WS-TOTAL-INT TO ACCT-CURR-BAL, zero both cycle buckets,
        // REWRITE. Always invoked (even with a zero total) so the cycle accumulators are reset.
        accountBalanceUpdater.applyInterestAndCloseCycle(account, wsTotalInt);
    }

    /**
     * Resolves the disclosure-group interest rate with the {@code 'DEFAULT'} fallback (PR-02), porting
     * {@code CBACT04C} paragraphs {@code 1200-GET-INTEREST-RATE} and {@code 1200-A-GET-DEFAULT-INT-RATE}
     * [app/cbl/CBACT04C.cbl L415-L460].
     *
     * <p>The account-specific key {@code (groupId, typeCd, catCd)} is tried first; on a miss (the COBOL
     * {@code '23'} INVALID KEY status) the lookup is retried with {@code groupId = "DEFAULT"}. If the
     * DEFAULT lookup also misses, a {@link DiscloseGroupNotFoundException} is thrown, mirroring the
     * COBOL {@code 9999-ABEND-PROGRAM} branch. A found group's rate is returned as-is (it may be zero,
     * in which case the caller's rate gate skips interest for that row).</p>
     *
     * @param groupId the account group id ({@code ACCT-GROUP-ID})
     * @param typeCd  the transaction type code ({@code TRANCAT-TYPE-CD})
     * @param catCd   the transaction category code ({@code TRANCAT-CD})
     * @return the disclosure interest rate (precision 6, scale 2)
     * @throws DiscloseGroupNotFoundException if neither the account-specific nor the {@code DEFAULT}
     *         disclosure group exists for the given type/category
     */
    private BigDecimal lookupInterestRate(String groupId, String typeCd, String catCd) {
        DisclosureGroupId primaryKey = new DisclosureGroupId(groupId, typeCd, catCd);
        Optional<DisclosureGroup> result = disclosureGroupRepository.findById(primaryKey);

        if (result.isEmpty()) {
            log.debug("Disclosure group miss for {}/{}/{}; retrying with DEFAULT", groupId, typeCd, catCd);
            DisclosureGroupId defaultKey = new DisclosureGroupId(DEFAULT_GROUP_ID, typeCd, catCd);
            result = disclosureGroupRepository.findById(defaultKey);
        }

        return result
                .map(DisclosureGroup::getDisIntRate)
                .orElseThrow(() -> new DiscloseGroupNotFoundException(groupId, typeCd, catCd));
    }

    /**
     * Builds and persists one interest transaction, porting {@code CBACT04C} paragraph
     * {@code 1300-B-WRITE-TX} [app/cbl/CBACT04C.cbl L473-L500].
     *
     * <p>Field mapping: ID = {@code parmDate(10) + suffix(6)} (PR-10); type {@value #INTEREST_TRAN_TYPE};
     * category {@value #INTEREST_TRAN_CAT}; source {@value #INTEREST_TRAN_SOURCE}; description
     * {@code "Int. for a/c " + } the 11-digit zero-padded account id; amount = the monthly interest at
     * scale 2 (PR-16); merchant id {@value #INTEREST_MERCHANT_ID} with blank merchant name/city/zip;
     * card number from the account cross-reference. Both {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}
     * receive the same DB2-format instant (PR-11), stored as {@code LocalDateTime}.</p>
     *
     * @param accountId       the account id (drives the description and equals {@code ACCT-ID})
     * @param monthlyInterest the computed monthly interest amount ({@code WS-MONTHLY-INT} / {@code TRAN-AMT})
     * @param parmDate        the validated 10-character run date (transaction-ID prefix)
     * @param cardNum         the card number resolved from the account cross-reference ({@code XREF-CARD-NUM})
     */
    private void emitInterestTransaction(Long accountId, BigDecimal monthlyInterest, String parmDate, String cardNum) {
        Transaction tx = new Transaction();

        // PR-10: 16-char ID = parmDate(10) + 6-digit sequential suffix.
        tx.setTranId(transactionIdGenerator.nextBatchId(parmDate));

        tx.setTypeCd(INTEREST_TRAN_TYPE);                                      // MOVE '01' TO TRAN-TYPE-CD
        tx.setCategoryCd(INTEREST_TRAN_CAT);                                   // MOVE '05' TO TRAN-CAT-CD (PIC 9(04) -> "0005")
        tx.setSource(INTEREST_TRAN_SOURCE);                                    // MOVE 'System' TO TRAN-SOURCE
        tx.setDescription(INTEREST_TRAN_DESC_PREFIX                            // STRING 'Int. for a/c ' ACCT-ID
                + String.format(ACCT_ID_DESC_FORMAT, accountId));
        tx.setAmount(monthlyInterest.setScale(BigDecimalUtil.SCALE_TWO, RoundingMode.HALF_UP)); // MOVE WS-MONTHLY-INT TO TRAN-AMT
        tx.setMerchantId(INTEREST_MERCHANT_ID);                               // MOVE 0 TO TRAN-MERCHANT-ID
        tx.setMerchantName(EMPTY_MERCHANT_FIELD);                            // MOVE SPACES TO TRAN-MERCHANT-NAME
        tx.setMerchantCity(EMPTY_MERCHANT_FIELD);                            // MOVE SPACES TO TRAN-MERCHANT-CITY
        tx.setMerchantZip(EMPTY_MERCHANT_FIELD);                             // MOVE SPACES TO TRAN-MERCHANT-ZIP
        tx.setCardNum(cardNum);                                               // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM

        // PR-11: single DB2-format instant for both timestamps. Round-trip through the DB2 string so
        // the stored LocalDateTime carries the same centisecond precision the COBOL DB2-MIL PIC 9(02)
        // emits (Z-GET-DB2-FORMAT-TIMESTAMP; MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS / TRAN-PROC-TS).
        var nowDb2 = DateConversionUtil.fromDb2Timestamp(DateConversionUtil.nowAsDb2Timestamp());
        tx.setOrigTimestamp(nowDb2);
        tx.setProcTimestamp(nowDb2);

        // WRITE FD-TRANFILE-REC FROM TRAN-RECORD.
        transactionRepository.save(tx);
    }

    /**
     * Groups transaction-category-balance rows by account id into a {@link TreeMap}, yielding ascending
     * {@code account_id} iteration order. This reproduces the deterministic account sequence the COBOL
     * obtains from the physical {@code TRAN-CAT-KEY} ordering of the {@code TCATBAL} file, which is
     * required for stable interest-transaction-ID assignment and reproducible parity output.
     *
     * @param all all transaction-category-balance rows
     * @return an account-id-ordered map from account id to that account's rows
     */
    private static Map<Long, List<TransactionCategoryBalance>> groupByAccount(
            List<TransactionCategoryBalance> all) {
        Map<Long, List<TransactionCategoryBalance>> grouped = new TreeMap<>();
        for (TransactionCategoryBalance balance : all) {
            Long acctId = extractAccountId(balance);
            grouped.computeIfAbsent(acctId, key -> new ArrayList<>()).add(balance);
        }
        return grouped;
    }

    /**
     * Extracts the account id from a balance row's composite key
     * ({@code TransactionCategoryBalanceId.accountId}, COBOL {@code TRANCAT-ACCT-ID}).
     *
     * @param balance the balance row
     * @return the account id, or {@code null} if the embedded id is absent
     */
    private static Long extractAccountId(TransactionCategoryBalance balance) {
        return balance.getId() != null ? balance.getId().getAccountId() : null;
    }

    /**
     * Extracts the transaction type code from a balance row's composite key
     * ({@code TransactionCategoryBalanceId.typeCd}, COBOL {@code TRANCAT-TYPE-CD}).
     *
     * @param balance the balance row
     * @return the type code, or {@code null} if the embedded id is absent
     */
    private static String extractTypeCd(TransactionCategoryBalance balance) {
        return balance.getId() != null ? balance.getId().getTypeCd() : null;
    }

    /**
     * Extracts the transaction category code from a balance row's composite key
     * ({@code TransactionCategoryBalanceId.categoryCd}, COBOL {@code TRANCAT-CD}). The four-character
     * fixed-width value (e.g. {@code "0001"}) is preserved as-is for the disclosure-group lookup.
     *
     * @param balance the balance row
     * @return the category code, or {@code null} if the embedded id is absent
     */
    private static String extractCategoryCd(TransactionCategoryBalance balance) {
        return balance.getId() != null ? balance.getId().getCategoryCd() : null;
    }
}
