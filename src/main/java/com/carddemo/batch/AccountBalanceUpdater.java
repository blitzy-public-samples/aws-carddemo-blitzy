package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import com.carddemo.util.BigDecimalUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spring-managed component that ports the two account-record balance-update paragraphs of the
 * CardDemo batch suite to Java/Spring Boot, preserving their packed-decimal arithmetic
 * line-by-line.
 *
 * <p>It is the single, shared "REWRITE the {@code ACCOUNT-RECORD}" sink used by both critical
 * batch flows:</p>
 * <ul>
 *   <li><strong>POSTTRAN</strong> (transaction posting) calls
 *       {@link #applyDailyTransaction(Account, BigDecimal)} once for every <em>accepted</em>
 *       daily transaction, from the {@code CompositeItemWriter} of
 *       {@code TransactionPostingJobConfig}.</li>
 *   <li><strong>INTCALC</strong> (interest calculation) calls
 *       {@link #applyInterestAndCloseCycle(Account, BigDecimal)} once per account, from the
 *       account-update step of {@code InterestCalculationJobConfig}, after the per-category
 *       monthly interest has been totalled.</li>
 * </ul>
 *
 * <h2>COBOL source mapping (preserved EXACTLY)</h2>
 * <table border="1">
 *   <caption>Account-record update paragraphs reproduced by this component</caption>
 *   <tr><th>Java method</th><th>COBOL paragraph</th><th>Source</th><th>Rule</th></tr>
 *   <tr>
 *     <td>{@link #applyDailyTransaction(Account, BigDecimal)}</td>
 *     <td>{@code 2800-UPDATE-ACCOUNT-REC}</td>
 *     <td>{@code app/cbl/CBTRN02C.cbl} L545-L560</td>
 *     <td>PR-07</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #applyInterestAndCloseCycle(Account, BigDecimal)}</td>
 *     <td>{@code 1050-UPDATE-ACCOUNT}</td>
 *     <td>{@code app/cbl/CBACT04C.cbl} L350-L370</td>
 *     <td>PR-08</td>
 *   </tr>
 * </table>
 *
 * <h2>PR-07 &mdash; sign-based bucket (CBTRN02C {@code 2800-UPDATE-ACCOUNT-REC})</h2>
 * <pre>
 *   ADD DALYTRAN-AMT  TO ACCT-CURR-BAL
 *   IF DALYTRAN-AMT &gt;= 0
 *      ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
 *   ELSE
 *      ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
 *   END-IF
 *   REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
 * </pre>
 * <p><strong>Critical insight:</strong> the COBOL {@code ADD} of a <em>negative</em>
 * {@code DALYTRAN-AMT} into {@code ACCT-CURR-CYC-DEBIT} accumulates the debit bucket as a
 * <em>negative</em> value. This component preserves that behaviour by adding the (signed)
 * amount directly &mdash; it never takes an absolute value. The downstream over-limit check
 * {@code ACCT-CREDIT-LIMIT &lt; (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)}
 * (code 102) therefore computes {@code CREDIT - (negative DEBIT)} = {@code CREDIT + abs(DEBIT)}
 * exactly as the mainframe intended.</p>
 *
 * <h2>PR-08 &mdash; interest application + cycle close (CBACT04C {@code 1050-UPDATE-ACCOUNT})</h2>
 * <pre>
 *   ADD WS-TOTAL-INT  TO ACCT-CURR-BAL
 *   MOVE 0 TO ACCT-CURR-CYC-CREDIT
 *   MOVE 0 TO ACCT-CURR-CYC-DEBIT
 *   REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
 * </pre>
 * <p>After interest is folded into the running balance, both cycle accumulators are reset to
 * exactly zero (the {@code MOVE 0 TO} statements), starting a fresh billing cycle.</p>
 *
 * <h2>Cross-cutting rules</h2>
 * <ul>
 *   <li><strong>PR-16 (money is {@link BigDecimal}, scale 2, HALF_UP).</strong> Every monetary
 *       operation flows through {@link BigDecimalUtil#scaledAdd(BigDecimal, BigDecimal)} or an
 *       explicit {@code setScale(2, RoundingMode.HALF_UP)}, replicating the COBOL
 *       {@code PIC S9(10)V99} packed-decimal semantics. No {@code float}/{@code double} is used.</li>
 *   <li><strong>PR-22 (optimistic locking).</strong> Persistence is performed via the inherited
 *       {@link AccountRepository#save(Object)} on the managed {@link Account} entity, which carries
 *       a {@code @Version} column. A concurrent modification surfaces as an
 *       {@code OptimisticLockException} (mapped to HTTP 409 by the global handler), replacing the
 *       VSAM CI-level exclusive {@code READ UPDATE} lock.</li>
 *   <li><strong>PR-23 (lock ordering).</strong> Within the POSTTRAN writer chain this updater is
 *       invoked last (after {@code CUSTOMER -> ACCOUNT -> CARD -> TRANSACTION} processing),
 *       matching the documented VSAM lock-acquisition order.</li>
 *   <li><strong>PR-24 (unit-of-work boundary).</strong> Both update methods declare
 *       {@link Propagation#MANDATORY}; they MUST be invoked inside an already-active transaction
 *       (the surrounding Spring Batch chunk transaction), failing fast with an
 *       {@code IllegalTransactionStateException} otherwise. This reproduces the implicit CICS
 *       {@code SYNCPOINT}/UOW bracket and the COBOL {@code REWRITE} being part of the same
 *       logical unit as the reads that preceded it.</li>
 *   <li><strong>PR-29 (constructor injection).</strong> The sole {@code final}
 *       {@link AccountRepository} collaborator is injected through the Lombok
 *       {@code @RequiredArgsConstructor}-generated constructor; no field injection is used.</li>
 * </ul>
 *
 * <p>The component is stateless and therefore thread-safe; the only mutable state lives in the
 * {@link Account} arguments, which are owned by the calling step/chunk.</p>
 *
 * @see com.carddemo.entity.Account
 * @see com.carddemo.repository.AccountRepository
 * @see com.carddemo.util.BigDecimalUtil
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountBalanceUpdater {

    /**
     * Spring Data JPA repository for {@link Account} master records. Its inherited
     * {@code save(Account)} is the JPA equivalent of the COBOL {@code REWRITE FD-ACCTFILE-REC}
     * and is invoked at the end of both update methods to persist the mutated account (PR-07 /
     * PR-08), with {@code @Version} optimistic locking enforced on flush (PR-22).
     */
    private final AccountRepository accountRepository;

    /**
     * Applies one accepted daily-transaction amount to an account, preserving the COBOL
     * {@code 2800-UPDATE-ACCOUNT-REC} paragraph [app/cbl/CBTRN02C.cbl L545-L560] line-by-line
     * (PR-07).
     *
     * <p>Semantics:</p>
     * <ol>
     *   <li>{@code currBal += amount} (unconditional &mdash; {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL}).</li>
     *   <li>If {@code amount >= 0}: {@code currCycCredit += amount}
     *       ({@code ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT}).</li>
     *   <li>Else: {@code currCycDebit += amount} ({@code ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT})
     *       &mdash; the signed (negative) amount is added directly so the debit bucket accumulates
     *       as a negative value, exactly as the COBOL does. <strong>No absolute value is taken.</strong></li>
     *   <li>The mutated account is persisted via {@link AccountRepository#save(Object)}
     *       (the {@code REWRITE}).</li>
     * </ol>
     *
     * <p>All arithmetic uses {@link BigDecimalUtil#scaledAdd(BigDecimal, BigDecimal)} (scale 2,
     * {@link RoundingMode#HALF_UP}) per PR-16. Null balance/bucket fields on the incoming entity
     * are treated as {@code 0.00}.</p>
     *
     * @param account the managed account to update (must not be {@code null})
     * @param amount  the signed daily-transaction amount {@code DALYTRAN-AMT} (must not be
     *                {@code null}); positive values post to the credit bucket, negative values to
     *                the debit bucket
     * @return the saved {@link Account} returned by the repository (the {@code REWRITE} result)
     * @throws IllegalArgumentException if {@code account} or {@code amount} is {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Account applyDailyTransaction(Account account, BigDecimal amount) {
        if (account == null) {
            throw new IllegalArgumentException("account must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }

        // Normalize the incoming amount to the COBOL PIC S9(10)V99 scale (2 decimals, HALF_UP).
        BigDecimal scaledAmt = amount.setScale(2, RoundingMode.HALF_UP);

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL  (unconditional, regardless of sign).
        BigDecimal currBal = nullSafe(account.getCurrBal());
        account.setCurrBal(BigDecimalUtil.scaledAdd(currBal, scaledAmt));

        // IF DALYTRAN-AMT >= 0 -> credit bucket; ELSE -> debit bucket (signed value, no abs()).
        if (scaledAmt.signum() >= 0) {
            BigDecimal currCycCredit = nullSafe(account.getCurrCycCredit());
            account.setCurrCycCredit(BigDecimalUtil.scaledAdd(currCycCredit, scaledAmt));
        } else {
            BigDecimal currCycDebit = nullSafe(account.getCurrCycDebit());
            account.setCurrCycDebit(BigDecimalUtil.scaledAdd(currCycDebit, scaledAmt));
        }

        // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD.
        Account saved = accountRepository.save(account);
        log.debug("Applied transaction amount={} to account={} new currBal={}",
                scaledAmt, account.getAcctId(), saved.getCurrBal());
        return saved;
    }

    /**
     * Folds the period's total interest into an account's balance and closes the billing cycle by
     * zeroing both cycle accumulators, preserving the COBOL {@code 1050-UPDATE-ACCOUNT} paragraph
     * [app/cbl/CBACT04C.cbl L350-L370] line-by-line (PR-08).
     *
     * <p>Semantics:</p>
     * <ol>
     *   <li>{@code currBal += totalInterest} ({@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}).</li>
     *   <li>{@code currCycCredit = 0.00} ({@code MOVE 0 TO ACCT-CURR-CYC-CREDIT}).</li>
     *   <li>{@code currCycDebit = 0.00} ({@code MOVE 0 TO ACCT-CURR-CYC-DEBIT}).</li>
     *   <li>The mutated account is persisted via {@link AccountRepository#save(Object)}
     *       (the {@code REWRITE}).</li>
     * </ol>
     *
     * <p>Both cycle buckets are set to a scale-2 {@code 0.00} so that subsequent
     * {@link BigDecimal#compareTo(BigDecimal)} checks report exact equality with zero. Arithmetic
     * uses {@link BigDecimalUtil#scaledAdd(BigDecimal, BigDecimal)} (scale 2,
     * {@link RoundingMode#HALF_UP}) per PR-16; a null running balance is treated as {@code 0.00}.</p>
     *
     * @param account       the managed account to update (must not be {@code null})
     * @param totalInterest the accumulated interest {@code WS-TOTAL-INT} to add to the balance
     *                      (must not be {@code null})
     * @return the saved {@link Account} returned by the repository (the {@code REWRITE} result)
     * @throws IllegalArgumentException if {@code account} or {@code totalInterest} is {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Account applyInterestAndCloseCycle(Account account, BigDecimal totalInterest) {
        if (account == null) {
            throw new IllegalArgumentException("account must not be null");
        }
        if (totalInterest == null) {
            throw new IllegalArgumentException("totalInterest must not be null");
        }

        // Normalize the interest amount to the COBOL PIC S9(10)V99 scale (2 decimals, HALF_UP).
        BigDecimal scaledInt = totalInterest.setScale(2, RoundingMode.HALF_UP);

        // ADD WS-TOTAL-INT TO ACCT-CURR-BAL.
        BigDecimal currBal = nullSafe(account.getCurrBal());
        account.setCurrBal(BigDecimalUtil.scaledAdd(currBal, scaledInt));

        // MOVE 0 TO ACCT-CURR-CYC-CREDIT / MOVE 0 TO ACCT-CURR-CYC-DEBIT (end-of-cycle clearance).
        account.setCurrCycCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        account.setCurrCycDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD.
        Account saved = accountRepository.save(account);
        log.debug("Applied interest={} to account={} new currBal={} cycle buckets zeroed",
                scaledInt, account.getAcctId(), saved.getCurrBal());
        return saved;
    }

    /**
     * Returns the given monetary value, or a scale-2 {@code 0.00} when it is {@code null}.
     *
     * <p>Mirrors the COBOL zero-initialized working-storage / record fields these calculations
     * replace: a freshly seeded account always has numeric (never {@code NULL}) cycle balances, but
     * defensive null handling keeps the tight per-transaction loop free of
     * {@link NullPointerException} risk. The returned zero carries scale 2 so that downstream
     * {@link BigDecimal#compareTo(BigDecimal)} comparisons behave consistently with the rest of the
     * money model (PR-16).</p>
     *
     * @param value the value to null-check (may be {@code null})
     * @return {@code value} if non-null, otherwise {@code 0.00}
     */
    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value;
    }
}
