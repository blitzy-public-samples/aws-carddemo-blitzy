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
import java.util.Optional;

/**
 * Spring-managed component that ports the {@code 2700-UPDATE-TCATBAL} paragraph family of the
 * CardDemo transaction-posting batch program ({@code app/cbl/CBTRN02C.cbl}) to Java/Spring Boot,
 * preserving its read-by-composite-key / create-or-update packed-decimal semantics line-by-line
 * (PR-06).
 *
 * <p>It is the single, shared "upsert the {@code TRAN-CAT-BAL-RECORD}" sink invoked once per
 * <em>accepted</em> daily transaction from the POSTTRAN composite writer chain
 * ({@code TransactionPostingJobConfig}), alongside the sibling {@link AccountBalanceUpdater}
 * (account REWRITE) and the {@code TransactionRepository.save} (transaction WRITE). The same
 * upsert is reused by the {@code InterestCalculationTasklet} when posting computed interest into
 * the per-account/type/category running totals.</p>
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
 * {@link Optional#empty()}; the {@code WRITE} (insert) and {@code REWRITE} (update) both map to
 * {@link TransactionCategoryBalanceRepository#save(Object)} (JPA {@code merge}). Because the
 * composite key {@code (account_id, type_cd, cat_cd)} fully determines record identity, the single
 * {@code findById} subsumes the COBOL {@code MOVE 'N'/'Y' WS-CREATE-TRANCAT-REC} probe:
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
 *       {@code scaledAdd(existingBalance, amount)} on the managed entity (REWRITE).</li>
 * </ul>
 *
 * <p><strong>Critical insight (raw signed amount).</strong> The COBOL {@code ADD} of a
 * <em>negative</em> {@code DALYTRAN-AMT} decrements the category balance; for a missing key it
 * creates a record whose balance is the negative amount. This component preserves that behaviour
 * by adding the signed amount directly &mdash; it <strong>never</strong> takes an absolute value
 * and never special-cases the sign.</p>
 *
 * <h2>Public API</h2>
 * <p>Four entry points all funnel through the single read-modify-write core
 * {@link #upsert(TransactionCategoryBalanceId, BigDecimal)} so the COBOL semantics live in exactly
 * one place:</p>
 * <ul>
 *   <li>{@link #upsertBalance(Long, String, Integer, BigDecimal)} &mdash; the canonical entry point
 *       (matches the COBOL key fields {@code TRANCAT-ACCT-ID} / {@code TRANCAT-TYPE-CD} /
 *       {@code TRANCAT-CD}). The numeric four-digit category code (COBOL {@code PIC 9(04)}) is
 *       zero-padded to the four-character {@code cat_cd CHAR(4)} representation
 *       (e.g. {@code 1 -> "0001"}, {@code 5 -> "0005"}) before the composite key is assembled,
 *       so it matches the seeded rows and the sibling {@code InterestCalculationTasklet} which
 *       posts to category {@code "0005"}.</li>
 *   <li>{@link #upsertBalance(Long, String, String, BigDecimal)} &mdash; convenience overload for
 *       feeds (CSV / fixed-width DALYTRAN) that carry the category code as a numeric string
 *       (e.g. {@code "0001"}); it normalizes the value and delegates to the {@code Integer}
 *       overload.</li>
 *   <li>{@link #upsert(TransactionCategoryBalanceId, BigDecimal)} &mdash; the core upsert keyed by a
 *       pre-built composite key.</li>
 *   <li>{@link #upsert(DailyTransaction, Long)} &mdash; assembles the key from an accepted
 *       {@link DailyTransaction} and the cross-reference-resolved account id, mirroring the COBOL
 *       key-assembly at the top of {@code 2700-UPDATE-TCATBAL}.</li>
 * </ul>
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
 *   <li><strong>PR-24 (unit-of-work boundary).</strong> Every public method declares
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
     * Number of digits in the COBOL {@code TRANCAT-CD PIC 9(04)} category code; the numeric
     * {@code Integer} category passed to {@link #upsertBalance(Long, String, Integer, BigDecimal)}
     * is zero-padded to this width to produce the four-character {@code cat_cd CHAR(4)} key
     * component (e.g. {@code 1 -> "0001"}).
     */
    private static final String CATEGORY_CODE_FORMAT = "%04d";

    /**
     * Spring Data JPA repository for {@link TransactionCategoryBalance} records, keyed by the
     * {@link TransactionCategoryBalanceId} composite key. Its {@code findById} reproduces the
     * COBOL {@code READ TCATBAL-FILE ... INVALID KEY} keyed read, and its inherited {@code save}
     * is the JPA equivalent of both the {@code WRITE} (insert) and {@code REWRITE} (update).
     */
    private final TransactionCategoryBalanceRepository repository;

    /**
     * Canonical TCATBAL upsert entry point keyed by the discrete COBOL key fields, preserving the
     * {@code 2700-UPDATE-TCATBAL} paragraph family [app/cbl/CBTRN02C.cbl L467-L532] line-by-line
     * (PR-06).
     *
     * <p>The numeric category code (COBOL {@code TRANCAT-CD PIC 9(04)}) is zero-padded to the
     * four-character {@code cat_cd CHAR(4)} representation before the composite key is assembled
     * (e.g. {@code 1 -> "0001"}, {@code 5 -> "0005"}) so the lookup matches the seeded rows and the
     * sibling interest-posting tasklet (PR-15). All arithmetic is delegated to
     * {@link #upsert(TransactionCategoryBalanceId, BigDecimal)} (scale 2, {@link RoundingMode#HALF_UP},
     * signed add &mdash; PR-16).</p>
     *
     * @param acctId TRANCAT-ACCT-ID (COBOL {@code PIC 9(11)}) &mdash; resolved from the card
     *               cross-reference ({@code XREF-ACCT-ID}); must not be {@code null}
     * @param typeCd TRANCAT-TYPE-CD (COBOL {@code PIC X(02)}) &mdash; two-character transaction
     *               type code; must not be {@code null}
     * @param catCd  TRANCAT-CD (COBOL {@code PIC 9(04)}) &mdash; numeric four-digit transaction
     *               category code; must not be {@code null}
     * @param amount DALYTRAN-AMT (COBOL {@code PIC S9(09)V99}) &mdash; signed daily-transaction
     *               amount to fold into the running balance; must not be {@code null}
     * @return the saved {@link TransactionCategoryBalance} (the {@code WRITE}/{@code REWRITE} result)
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionCategoryBalance upsertBalance(
            Long acctId, String typeCd, Integer catCd, BigDecimal amount) {
        if (acctId == null) {
            throw new IllegalArgumentException("acctId must not be null");
        }
        if (typeCd == null) {
            throw new IllegalArgumentException("typeCd must not be null");
        }
        if (catCd == null) {
            throw new IllegalArgumentException("catCd must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        return upsert(buildId(acctId, typeCd, catCd), amount);
    }

    /**
     * Convenience overload that accepts the category code as the numeric {@code String} encoded in
     * the DALYTRAN feed (CSV / fixed-width), e.g. {@code "0001"}.
     *
     * <p>The string is parsed to an {@code int} (after trimming surrounding whitespace) and
     * delegated to {@link #upsertBalance(Long, String, Integer, BigDecimal)}, which re-formats it to
     * the canonical four-character {@code cat_cd CHAR(4)} representation. This normalizes any
     * equivalent encodings of the same code ({@code "1"}, {@code "01"}, {@code "0001"},
     * {@code " 1 "}) to the single seeded form {@code "0001"} (PR-15).</p>
     *
     * @param acctId   TRANCAT-ACCT-ID (COBOL {@code PIC 9(11)}); must not be {@code null}
     * @param typeCd   TRANCAT-TYPE-CD (COBOL {@code PIC X(02)}); must not be {@code null}
     * @param catCdStr TRANCAT-CD (COBOL {@code PIC 9(04)}) as a numeric string; must not be
     *                 {@code null} and must parse as an integer
     * @param amount   DALYTRAN-AMT (COBOL {@code PIC S9(09)V99}) signed amount; must not be
     *                 {@code null}
     * @return the saved {@link TransactionCategoryBalance} (the {@code WRITE}/{@code REWRITE} result)
     * @throws IllegalArgumentException if {@code catCdStr} is {@code null}, or any other argument is
     *                                  {@code null}
     * @throws NumberFormatException    if {@code catCdStr} does not parse as an integer
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionCategoryBalance upsertBalance(
            Long acctId, String typeCd, String catCdStr, BigDecimal amount) {
        if (catCdStr == null) {
            throw new IllegalArgumentException("catCdStr must not be null");
        }
        Integer catCd = Integer.parseInt(catCdStr.trim());
        return upsertBalance(acctId, typeCd, catCd, amount);
    }

    /**
     * Upserts the transaction-category balance for the given composite key by the signed amount,
     * preserving the COBOL {@code 2700-UPDATE-TCATBAL} paragraph family
     * [app/cbl/CBTRN02C.cbl L467-L532] line-by-line (PR-06). This is the single read-modify-write
     * core through which every public entry point flows.
     *
     * <p>Semantics:</p>
     * <ol>
     *   <li>Read the existing record by composite key
     *       ({@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD}).</li>
     *   <li>If <strong>not found</strong> ({@code INVALID KEY} &rarr; {@link Optional#empty()}):
     *       create a new record whose balance starts at zero and add the (signed) amount &mdash;
     *       {@code 2700-A-CREATE-TCATBAL-REC} ({@code INITIALIZE} then {@code ADD DALYTRAN-AMT};
     *       {@code WRITE}).</li>
     *   <li>If <strong>found</strong> ({@code NOT INVALID KEY}): add the (signed) amount to the
     *       existing balance on the managed entity &mdash; {@code 2700-B-UPDATE-TCATBAL-REC}
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
        Optional<TransactionCategoryBalance> existing = repository.findById(key);

        TransactionCategoryBalance entity;
        if (existing.isEmpty()) {
            // 2700-A-CREATE-TCATBAL-REC: INITIALIZE (TRAN-CAT-BAL := 0) then ADD DALYTRAN-AMT; WRITE.
            // The starting balance is therefore exactly the (signed) amount.
            BigDecimal initial = BigDecimalUtil.scaledAdd(BigDecimalUtil.ZERO, scaledAmt);
            entity = new TransactionCategoryBalance(key, initial);
            log.debug("Created TCATBAL key=({},{},{}) with starting balance {}",
                    key.getAccountId(), key.getTypeCd(), key.getCategoryCd(), initial);
        } else {
            // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL; REWRITE. The same managed
            // entity is mutated and re-saved so JPA dirty-checking issues the equivalent of REWRITE.
            entity = existing.get();
            BigDecimal current = nullSafe(entity.getTranCatBal());
            BigDecimal updated = BigDecimalUtil.scaledAdd(current, scaledAmt);
            entity.setTranCatBal(updated);
            log.debug("Updated TCATBAL key=({},{},{}) by {} -> new balance {}",
                    key.getAccountId(), key.getTypeCd(), key.getCategoryCd(), scaledAmt, updated);
        }

        return repository.save(entity);
    }

    /**
     * Convenience overload that derives the {@link TransactionCategoryBalanceId} from an accepted
     * {@link DailyTransaction} and the account id resolved by the cross-reference lookup, then
     * delegates to {@link #upsert(TransactionCategoryBalanceId, BigDecimal)}.
     *
     * <p>Mirrors the COBOL key assembly at the top of {@code 2700-UPDATE-TCATBAL}
     * ({@code MOVE XREF-ACCT-ID / DALYTRAN-TYPE-CD / DALYTRAN-CAT-CD}): the account id originates
     * from the card cross-reference ({@code XREF-ACCT-ID}, resolved in {@code 1500-A-LOOKUP-XREF}),
     * while the type and category codes come directly off the daily-transaction record (already in
     * their canonical fixed-width form).</p>
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
     * Builds the {@link TransactionCategoryBalanceId} composite key from the discrete COBOL key
     * fields, zero-padding the numeric category code to the four-character {@code cat_cd CHAR(4)}
     * representation (PR-15).
     *
     * <p>The COBOL {@code TRANCAT-CD PIC 9(04)} is a four-digit numeric; the relational column is a
     * fixed-width {@code CHAR(4)} that preserves leading zeros (e.g. {@code "0001"}). Formatting the
     * {@code Integer} with {@value #CATEGORY_CODE_FORMAT} yields exactly that representation so the
     * key matches the seeded rows and the values written by the sibling interest-posting tasklet.</p>
     *
     * @param acctId the account id ({@code TRANCAT-ACCT-ID})
     * @param typeCd the two-character transaction type code ({@code TRANCAT-TYPE-CD})
     * @param catCd  the numeric four-digit transaction category code ({@code TRANCAT-CD})
     * @return a populated composite key
     */
    private TransactionCategoryBalanceId buildId(Long acctId, String typeCd, Integer catCd) {
        String categoryCd = String.format(CATEGORY_CODE_FORMAT, catCd);
        return new TransactionCategoryBalanceId(acctId, typeCd, categoryCd);
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
