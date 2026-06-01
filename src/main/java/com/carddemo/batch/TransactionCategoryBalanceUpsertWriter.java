package com.carddemo.batch;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.util.BigDecimalUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spring-managed component that ports the {@code 2700-UPDATE-TCATBAL} paragraph family of the
 * CardDemo transaction-posting batch program to Java/Spring Boot, preserving its
 * read-by-composite-key / create-or-update packed-decimal semantics line-by-line (PR-06).
 *
 * <p>It is the single, shared "upsert the {@code TRAN-CAT-BAL-RECORD}" sink invoked once per
 * <em>accepted</em> daily transaction from the POSTTRAN composite writer chain
 * ({@code TransactionPostingJobConfig}), alongside the sibling {@link AccountBalanceUpdater}
 * (account REWRITE) and the {@code TransactionRepository.save} (transaction WRITE).</p>
 *
 * <h2>COBOL source mapping (preserved EXACTLY)</h2>
 * <p>Reproduces {@code app/cbl/CBTRN02C.cbl} paragraph {@code 2700-UPDATE-TCATBAL} (L467-L500)
 * and its two helper paragraphs:</p>
 * <pre>
 *   2700-UPDATE-TCATBAL.
 *       MOVE XREF-ACCT-ID     TO FD-TRANCAT-ACCT-ID
 *       MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
 *       MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD
 *       MOVE 'N' TO WS-CREATE-TRANCAT-REC
 *       READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
 *          INVALID KEY
 *            MOVE 'Y' TO WS-CREATE-TRANCAT-REC          *&gt; record not found
 *       END-READ.
 *       IF WS-CREATE-TRANCAT-REC = 'Y'
 *          PERFORM 2700-A-CREATE-TCATBAL-REC            *&gt; INSERT
 *       ELSE
 *          PERFORM 2700-B-UPDATE-TCATBAL-REC            *&gt; UPDATE
 *       END-IF.
 *
 *   2700-A-CREATE-TCATBAL-REC.                          *&gt; INVALID KEY branch
 *       INITIALIZE TRAN-CAT-BAL-RECORD                  *&gt; TRAN-CAT-BAL := 0
 *       MOVE XREF-ACCT-ID     TO TRANCAT-ACCT-ID
 *       MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
 *       MOVE DALYTRAN-CAT-CD  TO TRANCAT-CD
 *       ADD  DALYTRAN-AMT     TO TRAN-CAT-BAL           *&gt; 0 + amount
 *       WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD.
 *
 *   2700-B-UPDATE-TCATBAL-REC.                          *&gt; NOT INVALID KEY branch
 *       ADD DALYTRAN-AMT TO TRAN-CAT-BAL                *&gt; existing + amount
 *       REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD.
 * </pre>
 *
 * <p>The COBOL {@code READ ... INVALID KEY} (file status {@code '23'}, record-not-found) maps to
 * the Spring Data {@link TransactionCategoryBalanceRepository#findById(Object)} returning
 * {@link java.util.Optional#empty()}; the {@code WRITE} (insert) and {@code REWRITE} (update) both
 * map to {@link TransactionCategoryBalanceRepository#save(Object)} (JPA {@code merge}). Because
 * the composite key {@code (account_id, type_cd, cat_cd)} fully determines record identity, a
 * single {@code findById} subsumes the COBOL {@code MOVE 'N'/'Y' WS-CREATE-TRANCAT-REC} probe:
 * {@code Optional.empty()} selects the create branch, a present value the update branch.</p>
 *
 * <h2>Create vs. update arithmetic (PR-06, preserved verbatim)</h2>
 * <ul>
 *   <li><strong>CREATE</strong> (2700-A) &mdash; {@code INITIALIZE} sets {@code TRAN-CAT-BAL} to
 *       zero, then {@code ADD DALYTRAN-AMT} yields a starting balance of exactly the (signed)
 *       transaction amount. Reproduced as {@code scaledAdd(0.00, amount)} on a freshly built
 *       {@link TransactionCategoryBalance}.</li>
 *   <li><strong>UPDATE</strong> (2700-B) &mdash; {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} adds the
 *       (signed) amount to the existing balance. Reproduced as
 *       {@code scaledAdd(existingBalance, amount)}.</li>
 * </ul>
 *
 * <p><strong>Critical insight (raw signed amount).</strong> The COBOL {@code ADD} of a
 * <em>negative</em> {@code DALYTRAN-AMT} decrements the category balance; for a missing key it
 * creates a record whose balance is the negative amount. This component preserves that behaviour
 * by adding the signed amount directly &mdash; it <strong>never</strong> takes an absolute value
 * and never special-cases the sign.</p>
 *
 * <h2>Cross-cutting rules</h2>
 * <ul>
 *   <li><strong>PR-06.</strong> The TCATBAL read-by-composite-key upsert semantics above are the
 *       primary contract of this class.</li>
 *   <li><strong>PR-15 (composite key fidelity).</strong> The {@link TransactionCategoryBalanceId}
 *       carries {@code (accountId, typeCd, categoryCd)} in the same field order as the COBOL key
 *       concatenation {@code FD-TRANCAT-ACCT-ID + FD-TRANCAT-TYPE-CD + FD-TRANCAT-CD}.</li>
 *   <li><strong>PR-16 (money is {@link BigDecimal}, scale 2, HALF_UP).</strong> Every monetary
 *       operation flows through {@link BigDecimalUtil#scaledAdd(BigDecimal, BigDecimal)} or an
 *       explicit {@code setScale(2, RoundingMode.HALF_UP)}, replicating the COBOL
 *       {@code PIC S9(09)V99} packed-decimal semantics. No {@code float}/{@code double} is used,
 *       and comparisons elsewhere rely on {@link BigDecimal#compareTo(BigDecimal)} (never
 *       {@code equals}).</li>
 *   <li><strong>PR-24 (unit-of-work boundary).</strong> Both upsert methods declare
 *       {@link Propagation#MANDATORY}; they MUST be invoked inside an already-active transaction
 *       (the surrounding Spring Batch chunk transaction), failing fast otherwise. This reproduces
 *       the implicit CICS {@code SYNCPOINT}/UOW bracket in which the original COBOL
 *       {@code WRITE}/{@code REWRITE} executed as part of the same logical unit as the reads that
 *       preceded it.</li>
 *   <li><strong>PR-29 (constructor injection).</strong> The sole {@code final}
 *       {@link TransactionCategoryBalanceRepository} collaborator is injected through the Lombok
 *       {@code @RequiredArgsConstructor}-generated constructor; no field injection is used.</li>
 * </ul>
 *
 * <p>The component is stateless and therefore thread-safe; the only mutable state lives in the
 * {@link TransactionCategoryBalance} entity it loads/creates within the caller's transaction.</p>
 *
 * @see com.carddemo.entity.TransactionCategoryBalance
 * @see com.carddemo.entity.TransactionCategoryBalanceId
 * @see com.carddemo.repository.TransactionCategoryBalanceRepository
 * @see com.carddemo.util.BigDecimalUtil
 * @see "app/cbl/CBTRN02C.cbl L467-L532 (paragraphs 2700-UPDATE-TCATBAL / 2700-A / 2700-B)"
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionCategoryBalanceUpsertWriter {

    /**
     * Spring Data JPA repository for {@link TransactionCategoryBalance} records, keyed by the
     * {@link TransactionCategoryBalanceId} composite key. Its {@code findById} reproduces the
     * COBOL {@code READ TCATBAL-FILE ... INVALID KEY} keyed read, and its inherited {@code save}
     * is the JPA equivalent of both the {@code WRITE} (insert) and {@code REWRITE} (update).
     */
    private final TransactionCategoryBalanceRepository repository;

    /**
     * Upserts the transaction-category balance for the given composite key by the signed amount,
     * preserving the COBOL {@code 2700-UPDATE-TCATBAL} paragraph family
     * [app/cbl/CBTRN02C.cbl L467-L532] line-by-line (PR-06).
     *
     * <p>Semantics:</p>
     * <ol>
     *   <li>Read the existing record by composite key
     *       ({@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD}).</li>
     *   <li>If <strong>not found</strong> ({@code INVALID KEY} &rarr; {@link java.util.Optional#empty()}):
     *       create a new record whose balance starts at zero and add the (signed) amount &mdash;
     *       {@code 2700-A-CREATE-TCATBAL-REC} ({@code INITIALIZE} then {@code ADD DALYTRAN-AMT};
     *       {@code WRITE}).</li>
     *   <li>If <strong>found</strong> ({@code NOT INVALID KEY}): add the (signed) amount to the
     *       existing balance &mdash; {@code 2700-B-UPDATE-TCATBAL-REC}
     *       ({@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}; {@code REWRITE}).</li>
     * </ol>
     *
     * <p>All arithmetic uses {@link BigDecimalUtil#scaledAdd(BigDecimal, BigDecimal)} (scale 2,
     * {@link RoundingMode#HALF_UP}) per PR-16; the amount is normalized to scale 2 on entry and the
     * (signed) value is added directly &mdash; no absolute value is taken (PR-06). A null existing
     * balance is treated as {@code 0.00} (defensive; the column is {@code NOT NULL}).</p>
     *
     * @param key    the composite key {@code (accountId, typeCd, categoryCd)} identifying the
     *               transaction-category balance (must not be {@code null})
     * @param amount the signed daily-transaction amount {@code DALYTRAN-AMT} to fold into the
     *               balance (must not be {@code null}); positive values increment, negative values
     *               decrement
     * @return the saved {@link TransactionCategoryBalance} (the {@code WRITE}/{@code REWRITE} result)
     * @throws IllegalArgumentException if {@code key} or {@code amount} is {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionCategoryBalance upsert(TransactionCategoryBalanceId key, BigDecimal amount) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }

        // Normalize the incoming amount to the COBOL PIC S9(09)V99 scale (2 decimals, HALF_UP).
        BigDecimal scaledAmt = amount.setScale(2, RoundingMode.HALF_UP);

        // READ TCATBAL-FILE ... INVALID KEY: Optional.empty() selects the create branch (2700-A),
        // a present value selects the update branch (2700-B). The composite key fully determines
        // record identity, so this single findById subsumes the COBOL WS-CREATE-TRANCAT-REC probe.
        return repository.findById(key)
                .map(existing -> {
                    // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL; REWRITE.
                    BigDecimal updated =
                            BigDecimalUtil.scaledAdd(nullSafe(existing.getTranCatBal()), scaledAmt);
                    existing.setTranCatBal(updated);
                    TransactionCategoryBalance saved = repository.save(existing);
                    log.debug("Updated TCATBAL key=({},{},{}) by {} -> new balance {}",
                            key.getAccountId(), key.getTypeCd(), key.getCategoryCd(),
                            scaledAmt, saved.getTranCatBal());
                    return saved;
                })
                .orElseGet(() -> {
                    // 2700-A-CREATE-TCATBAL-REC: INITIALIZE (TRAN-CAT-BAL := 0) then ADD DALYTRAN-AMT;
                    // WRITE. The starting balance is therefore exactly the (signed) amount.
                    BigDecimal initial = BigDecimalUtil.scaledAdd(BigDecimalUtil.ZERO, scaledAmt);
                    TransactionCategoryBalance created =
                            new TransactionCategoryBalance(key, initial);
                    TransactionCategoryBalance saved = repository.save(created);
                    log.debug("Created TCATBAL key=({},{},{}) with starting balance {}",
                            key.getAccountId(), key.getTypeCd(), key.getCategoryCd(),
                            saved.getTranCatBal());
                    return saved;
                });
    }

    /**
     * Convenience overload that derives the {@link TransactionCategoryBalanceId} from an accepted
     * {@link DailyTransaction} and the account id resolved by the cross-reference lookup, then
     * delegates to {@link #upsert(TransactionCategoryBalanceId, BigDecimal)}.
     *
     * <p>Mirrors the COBOL key assembly at the top of {@code 2700-UPDATE-TCATBAL}
     * ({@code MOVE XREF-ACCT-ID / DALYTRAN-TYPE-CD / DALYTRAN-CAT-CD}): the account id originates
     * from the card cross-reference ({@code XREF-ACCT-ID}, resolved in {@code 1500-A-LOOKUP-XREF}),
     * while the type and category codes come directly off the daily-transaction record.</p>
     *
     * @param tran      the accepted daily transaction supplying {@code typeCd}, {@code categoryCd},
     *                  and the signed {@code amount} (must not be {@code null})
     * @param accountId the account id resolved from the card cross-reference
     *                  ({@code XREF-ACCT-ID}; must not be {@code null})
     * @return the saved {@link TransactionCategoryBalance} (the {@code WRITE}/{@code REWRITE} result)
     * @throws IllegalArgumentException if {@code tran} or {@code accountId} is {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionCategoryBalance upsert(DailyTransaction tran, Long accountId) {
        if (tran == null) {
            throw new IllegalArgumentException("tran must not be null");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                accountId, tran.getTypeCd(), tran.getCategoryCd());
        return upsert(key, tran.getAmount());
    }

    /**
     * Returns the given monetary value, or a scale-2 {@code 0.00} when it is {@code null}.
     *
     * <p>The {@code balance} column is {@code NOT NULL}, so a persisted record always carries a
     * numeric balance; this defensive guard keeps the tight per-transaction loop free of
     * {@link NullPointerException} risk. The returned zero carries scale 2 so downstream
     * {@link BigDecimal#compareTo(BigDecimal)} comparisons behave consistently with the money model
     * (PR-16).</p>
     *
     * @param value the value to null-check (may be {@code null})
     * @return {@code value} if non-null, otherwise {@code 0.00}
     */
    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value;
    }
}
