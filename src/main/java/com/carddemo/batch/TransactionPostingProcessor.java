package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.util.BigDecimalUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Batch {@link ItemProcessor} that ports the transaction-validation chain of
 * {@code app/cbl/CBTRN02C.cbl} (the POSTTRAN batch program) to Java/Spring Boot.
 *
 * <p>This is the validation stage of the {@code TransactionPostingJobConfig} chunk step: it
 * consumes one {@link DailyTransaction} staging row at a time and emits a {@link ProcessingResult}
 * that tells the downstream {@code CompositeItemWriter} whether to <em>post</em> the transaction
 * (write {@code Transaction} + upsert {@code TransactionCategoryBalance} + update {@code Account})
 * or to <em>reject</em> it (write a {@code RejectedTransaction} carrying the validation code and
 * reason). The processor itself performs <strong>no writes</strong> &mdash; it is
 * {@code readOnly = true} and runs inside the surrounding chunk transaction
 * ({@link Propagation#MANDATORY}).</p>
 *
 * <h2>COBOL source mapping</h2>
 * <p>The logic is a line-by-line port of the following {@code CBTRN02C.cbl} paragraphs:</p>
 * <ul>
 *   <li>{@code 1500-VALIDATE-TRAN} (L370-L378) &mdash; the dispatcher: perform the XREF lookup,
 *       and only if it succeeded ({@code WS-VALIDATION-FAIL-REASON = 0}) perform the account
 *       lookup. This is the code-100 short-circuit reproduced below as an early return.</li>
 *   <li>{@code 1500-A-LOOKUP-XREF} (L380-L392) &mdash; {@code READ XREF-FILE} keyed on
 *       {@code DALYTRAN-CARD-NUM}; on {@code INVALID KEY} move {@code 100} /
 *       {@code 'INVALID CARD NUMBER FOUND'}.</li>
 *   <li>{@code 1500-B-LOOKUP-ACCT} (L393-L422) &mdash; {@code READ ACCOUNT-FILE} keyed on
 *       {@code XREF-ACCT-ID}; on {@code INVALID KEY} move {@code 101} /
 *       {@code 'ACCOUNT RECORD NOT FOUND'}; otherwise run the over-limit and expiration checks.</li>
 * </ul>
 *
 * <h2>Validation codes (PR-03 &mdash; preserved EXACTLY)</h2>
 * <table border="1">
 *   <caption>CBTRN02C {@code WS-VALIDATION-FAIL-REASON} values and descriptions</caption>
 *   <tr><th>Code</th><th>Message</th><th>COBOL origin</th></tr>
 *   <tr><td>{@code 100}</td><td>{@value #MSG_INVALID_CARD}</td><td>L385-L387 (XREF INVALID KEY)</td></tr>
 *   <tr><td>{@code 101}</td><td>{@value #MSG_ACCOUNT_NOT_FOUND}</td><td>L397-L399 (ACCOUNT INVALID KEY)</td></tr>
 *   <tr><td>{@code 102}</td><td>{@value #MSG_OVERLIMIT}</td><td>L410-L412 (over-limit)</td></tr>
 *   <tr><td>{@code 103}</td><td>{@value #MSG_EXPIRED}</td><td>L417-L419 (expiration)</td></tr>
 *   <tr><td>{@code 0}</td><td>(none &mdash; ACCEPTED)</td><td>all checks pass</td></tr>
 * </table>
 *
 * <h2>Check ordering and the over-limit / expiration last-writer-wins rule</h2>
 * <p>The lookup chain is strictly ordered XREF &rarr; ACCOUNT, because each step depends on data
 * resolved by the previous one (card number &rarr; account id &rarr; account fields). A code-100
 * failure short-circuits before the account lookup, and a code-101 failure short-circuits before
 * the balance checks &mdash; matching {@code 1500-VALIDATE-TRAN}'s guard and the COBOL
 * {@code INVALID KEY} branches.</p>
 *
 * <p><strong>Critical fidelity point (PR-03/PR-04/PR-05).</strong> Inside
 * {@code 1500-B-LOOKUP-ACCT NOT INVALID KEY} the over-limit check (L407-L413) and the expiration
 * check (L414-L420) are <em>two independent {@code IF...END-IF} blocks, not an {@code else-if}
 * chain</em>. Both blocks always execute. When a transaction is <em>both</em> over-limit
 * <em>and</em> past expiration, the over-limit block first sets
 * {@code WS-VALIDATION-FAIL-REASON = 102} (L410) and the expiration block then overwrites it with
 * {@code 103} (L417). The COBOL's final reason code for the both-fail case is therefore
 * {@code 103} (expired), <em>not</em> {@code 102}. This processor reproduces that last-writer-wins
 * behavior with an accumulator (no early return between the two checks); see
 * {@code TransactionPostingParityTest} which asserts this exact precedence. (The shorthand sample
 * in some AAP commentary that returns immediately on {@code 102} is incorrect for the both-fail
 * case; the COBOL source is the single source of truth per the migration's preservation mandate.)</p>
 *
 * <h2>Monetary arithmetic (PR-16)</h2>
 * <p>The credit-limit projection mirrors {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 * - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} (L403-L405) and the comparison mirrors
 * {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} (L407). All money values are {@link BigDecimal}
 * with scale {@code 2} and {@link RoundingMode#HALF_UP} (via {@link BigDecimalUtil}); comparisons
 * use {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}). {@code float}/{@code double}
 * are forbidden for any monetary value. Equality with the credit limit is <em>allowed</em>
 * (the COBOL uses {@code >=}); only a strictly greater projected position is over-limit.</p>
 *
 * <h2>Expiration comparison (PR-05)</h2>
 * <p>The COBOL compares the fixed-width text fields {@code ACCT-EXPIRAION-DATE} [sic] and
 * {@code DALYTRAN-ORIG-TS(1:10)} &mdash; the first 10 characters ({@code yyyy-MM-dd}) of the
 * 26-character DB2 timestamp. Because the entities normalize these to typed values
 * ({@link Account#getExpirationDate()} is a {@link LocalDate}; {@link DailyTransaction#getOrigTimestamp()}
 * is a {@link LocalDateTime}), this processor extracts the date portion with
 * {@link LocalDateTime#toLocalDate()} and compares with {@link LocalDate#isBefore(java.time.chrono.ChronoLocalDate)}.
 * For ISO {@code yyyy-MM-dd} values lexical order equals chronological order, so
 * {@code expirationDate.isBefore(tranDate)} is the exact, type-safe equivalent of the COBOL
 * {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} failure condition.</p>
 *
 * <h2>Design rules satisfied</h2>
 * <ul>
 *   <li><strong>PR-03</strong> &mdash; validation codes 100/101/102/103 with the exact COBOL messages.</li>
 *   <li><strong>PR-04</strong> &mdash; credit-limit formula operand order {@code creditLimit < (cycCredit - cycDebit + amount)}.</li>
 *   <li><strong>PR-05</strong> &mdash; expiration compares against the date portion of the original timestamp.</li>
 *   <li><strong>PR-16</strong> &mdash; {@link BigDecimal} scale 2 / {@link RoundingMode#HALF_UP}; {@code compareTo} not {@code equals}.</li>
 *   <li><strong>PR-23</strong> &mdash; read order XREF &rarr; ACCOUNT preserved (writes happen downstream).</li>
 *   <li><strong>PR-24</strong> &mdash; {@code @Transactional(propagation = MANDATORY, readOnly = true)} enlists in the chunk UOW (the CICS {@code SYNCPOINT} equivalent) without writing.</li>
 *   <li><strong>PR-29</strong> &mdash; constructor injection via Lombok {@link RequiredArgsConstructor}.</li>
 * </ul>
 *
 * @see DailyTransaction
 * @see CardXref
 * @see Account
 * @see CardXrefRepository
 * @see AccountRepository
 * @see BigDecimalUtil
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionPostingProcessor
        implements ItemProcessor<DailyTransaction, TransactionPostingProcessor.ProcessingResult> {

    /**
     * Card cross-reference repository. Backs {@code 1500-A-LOOKUP-XREF}: the daily-transaction
     * card number is the natural primary key of {@link CardXref}, so the keyed COBOL
     * {@code READ XREF-FILE} maps to the inherited {@code findById(String)} (there is no
     * {@code findByCardNum} method &mdash; the card number IS the key).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Account master repository. Backs {@code 1500-B-LOOKUP-ACCT}: the resolved
     * {@code XREF-ACCT-ID} is the primary key of {@link Account}, so the keyed COBOL
     * {@code READ ACCOUNT-FILE} maps to the inherited {@code findById(Long)}.
     */
    private final AccountRepository accountRepository;

    // ------------------------------------------------------------------------------------------
    // Validation code constants — preserved EXACTLY from CBTRN02C.cbl WS-VALIDATION-FAIL-REASON.
    // Declared public so the downstream RejectedTransaction writer can reference them.
    // ------------------------------------------------------------------------------------------

    /** Code 100: card number not found in the cross-reference file ({@code 1500-A-LOOKUP-XREF}, L385). */
    public static final short CODE_INVALID_CARD = 100;

    /** Code 101: account not found in the account file ({@code 1500-B-LOOKUP-ACCT} INVALID KEY, L397). */
    public static final short CODE_ACCOUNT_NOT_FOUND = 101;

    /** Code 102: projected position exceeds the account credit limit ({@code 1500-B-LOOKUP-ACCT}, L410). */
    public static final short CODE_OVERLIMIT = 102;

    /** Code 103: transaction date is after the account expiration date ({@code 1500-B-LOOKUP-ACCT}, L417). */
    public static final short CODE_EXPIRED = 103;

    // ------------------------------------------------------------------------------------------
    // Validation message constants — preserved EXACTLY from CBTRN02C.cbl
    // WS-VALIDATION-FAIL-REASON-DESC literals. DO NOT modify these strings (PR-03).
    // ------------------------------------------------------------------------------------------

    /** Exact COBOL literal for code 100 (L386). */
    public static final String MSG_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /** Exact COBOL literal for code 101 (L398). */
    public static final String MSG_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** Exact COBOL literal for code 102 (L411). */
    public static final String MSG_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /** Exact COBOL literal for code 103 (L418). */
    public static final String MSG_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * Validates a single daily-transaction staging row and returns the posting decision.
     *
     * <p>Reproduces {@code CBTRN02C 1500-VALIDATE-TRAN} and its sub-paragraphs. The check order is
     * strict and short-circuits on the first lookup failure, exactly mirroring the COBOL control
     * flow; the over-limit and expiration checks, however, both run (last-writer-wins) once the
     * account is resolved.</p>
     *
     * <p>Returning {@code null} signals Spring Batch to <em>filter</em> (skip) the item; this is
     * used only for the defensive {@code null} input guard. Every real staging row yields either an
     * {@link ProcessingResult.Status#ACCEPTED} result (carrying the resolved account id for the
     * writer) or a {@link ProcessingResult.Status#REJECTED} result (carrying the validation code and
     * exact COBOL reason). A rejected item does not abort the chunk &mdash; it is routed to the
     * reject sink, matching the COBOL {@code 2500-WRITE-REJECT-REC} behavior.</p>
     *
     * @param item the daily-transaction staging row to validate (may be {@code null})
     * @return {@code null} to skip a {@code null} input; otherwise an accepted or rejected
     *         {@link ProcessingResult}
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public ProcessingResult process(DailyTransaction item) {
        if (item == null) {
            // Spring Batch convention: a null result filters (skips) the item. There is no COBOL
            // equivalent (the PS file never yields a null record); this is a defensive guard.
            return null;
        }

        // ==========================================================================================
        // STEP 1 — 1500-A-LOOKUP-XREF (L380-L392): resolve the card number to an account via XREF.
        // COBOL: READ XREF-FILE ... INVALID KEY MOVE 100 'INVALID CARD NUMBER FOUND'.
        // ==========================================================================================
        final String cardNum = item.getCardNum();
        if (cardNum == null || cardNum.trim().isEmpty()) {
            // A blank/absent card number cannot key the XREF read; treat as INVALID KEY (code 100).
            log.warn("Rejecting daily transaction id={} — missing card number (code {})",
                    item.getDalytranId(), CODE_INVALID_CARD);
            return ProcessingResult.rejected(item, CODE_INVALID_CARD, MSG_INVALID_CARD);
        }

        final Optional<CardXref> xrefOpt = cardXrefRepository.findById(cardNum);
        if (xrefOpt.isEmpty()) {
            log.warn("Rejecting daily transaction id={} — card {} not found in cross-reference (code {})",
                    item.getDalytranId(), cardNum, CODE_INVALID_CARD);
            return ProcessingResult.rejected(item, CODE_INVALID_CARD, MSG_INVALID_CARD);
        }
        final Long accountId = xrefOpt.get().getAccountId();

        // ==========================================================================================
        // STEP 2 — 1500-B-LOOKUP-ACCT INVALID KEY (L393-L399): resolve the account by its id.
        // COBOL: READ ACCOUNT-FILE ... INVALID KEY MOVE 101 'ACCOUNT RECORD NOT FOUND'.
        // This runs only because the XREF lookup succeeded (1500-VALIDATE-TRAN guard, L372-L373).
        // ==========================================================================================
        final Optional<Account> acctOpt = (accountId == null)
                ? Optional.empty()
                : accountRepository.findById(accountId);
        if (acctOpt.isEmpty()) {
            log.warn("Rejecting daily transaction id={} — account {} not found (code {})",
                    item.getDalytranId(), accountId, CODE_ACCOUNT_NOT_FOUND);
            return ProcessingResult.rejected(item, CODE_ACCOUNT_NOT_FOUND, MSG_ACCOUNT_NOT_FOUND);
        }
        final Account account = acctOpt.get();

        // ==========================================================================================
        // STEPS 3 & 4 — 1500-B-LOOKUP-ACCT NOT INVALID KEY (L403-L420): over-limit then expiration.
        //
        // COBOL last-writer-wins: these are TWO independent IF...END-IF blocks (NOT else-if). Both
        // always run; when both fail, the expiration MOVE (L417) overwrites the over-limit MOVE
        // (L410), so the final code is 103. The accumulator below reproduces that precedence — note
        // there is deliberately NO early return between the two checks.
        // ==========================================================================================
        short failCode = 0;
        String failReason = null;

        // --- STEP 3: over-limit check (PR-04, code 102) ---------------------------------------
        // COBOL (L403-L413):
        //   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        //   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE MOVE 102 ... END-IF
        final BigDecimal creditLimit = BigDecimalUtil.nullSafe(account.getCreditLimit());
        final BigDecimal cycCredit = BigDecimalUtil.nullSafe(account.getCurrCycCredit());
        final BigDecimal cycDebit = BigDecimalUtil.nullSafe(account.getCurrCycDebit());
        // DALYTRAN-AMT is PIC S9(09)V99; normalize to scale 2 / HALF_UP so the projection is exact
        // (PR-16). The amount is used raw-signed: a negative (refund) reduces the projected position.
        final BigDecimal tranAmt = (item.getAmount() == null)
                ? BigDecimalUtil.ZERO
                : item.getAmount().setScale(BigDecimalUtil.SCALE_TWO, RoundingMode.HALF_UP);

        // WS-TEMP-BAL = (cycCredit - cycDebit) + tranAmt, kept at scale 2 throughout.
        final BigDecimal projectedPosition =
                BigDecimalUtil.scaledAdd(BigDecimalUtil.scaledSubtract(cycCredit, cycDebit), tranAmt);

        // Code 102 only when creditLimit < projectedPosition (equality is allowed by the COBOL >=).
        if (creditLimit.compareTo(projectedPosition) < 0) {
            failCode = CODE_OVERLIMIT;
            failReason = MSG_OVERLIMIT;
            log.warn("Daily transaction id={} is over limit (creditLimit={}, projectedPosition={}) (code {})",
                    item.getDalytranId(), creditLimit, projectedPosition, CODE_OVERLIMIT);
        }

        // --- STEP 4: expiration check (PR-05, code 103) ---------------------------------------
        // COBOL (L414-L420):
        //   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) CONTINUE ELSE MOVE 103 ... END-IF
        // This block runs regardless of the over-limit outcome and overwrites code 102 on failure.
        final LocalDate expirationDate = account.getExpirationDate();
        final LocalDateTime origTimestamp = item.getOrigTimestamp();
        if (expirationDate != null && origTimestamp != null) {
            // DALYTRAN-ORIG-TS(1:10) is the yyyy-MM-dd date portion of the 26-char DB2 timestamp.
            final LocalDate tranDate = origTimestamp.toLocalDate();
            if (expirationDate.isBefore(tranDate)) {
                failCode = CODE_EXPIRED;
                failReason = MSG_EXPIRED;
                log.warn("Daily transaction id={} received after account expiration "
                                + "(expirationDate={}, tranDate={}) (code {})",
                        item.getDalytranId(), expirationDate, tranDate, CODE_EXPIRED);
            }
        }

        if (failCode != 0) {
            return ProcessingResult.rejected(item, failCode, failReason);
        }

        // ==========================================================================================
        // ALL CHECKS PASSED — WS-VALIDATION-FAIL-REASON = 0. Accept and carry the resolved account
        // id so the downstream writer can post the transaction, upsert TCATBAL, and update Account.
        // ==========================================================================================
        if (log.isDebugEnabled()) {
            log.debug("Accepted daily transaction id={} for account {}", item.getDalytranId(), accountId);
        }
        return ProcessingResult.accepted(item, accountId);
    }

    /**
     * Immutable outcome of validating a single {@link DailyTransaction}.
     *
     * <p>Carries the posting decision from {@link TransactionPostingProcessor#process(DailyTransaction)}
     * to the {@code TransactionPostingJobConfig} {@code CompositeItemWriter}, which routes on
     * {@link #getStatus()}:</p>
     * <ul>
     *   <li>{@link Status#ACCEPTED} &mdash; the writer posts the transaction
     *       ({@code Transaction.save} + {@code TransactionCategoryBalance} upsert +
     *       {@code Account} balance update), using {@link #getResolvedAccountId()} (the
     *       {@code XREF-ACCT-ID} resolved during validation) to avoid a second cross-reference read.</li>
     *   <li>{@link Status#REJECTED} &mdash; the writer persists a {@code RejectedTransaction}
     *       (the COBOL {@code 2500-WRITE-REJECT-REC} / DALYREJS equivalent) carrying
     *       {@link #getValidationCode()} (100/101/102/103) and {@link #getRejectionReason()} (the
     *       exact COBOL message).</li>
     * </ul>
     *
     * <p>Instances are created only through the {@link #accepted(DailyTransaction, Long)} and
     * {@link #rejected(DailyTransaction, Short, String)} factory methods; the all-args constructor is
     * private to keep the two states well-formed (an accepted result never carries a code/reason; a
     * rejected result never carries a resolved account id).</p>
     */
    public static class ProcessingResult {

        /** Posting decision discriminator. */
        public enum Status {
            /** The transaction passed every validation check and may be posted. */
            ACCEPTED,
            /** The transaction failed a validation check and must be routed to the reject sink. */
            REJECTED
        }

        private final Status status;
        private final DailyTransaction dailyTransaction;
        private final Long resolvedAccountId;
        private final Short validationCode;
        private final String rejectionReason;

        /**
         * Canonical all-args constructor. Private by design &mdash; callers use the
         * {@link #accepted(DailyTransaction, Long)} / {@link #rejected(DailyTransaction, Short, String)}
         * factories so the two states cannot be mixed.
         *
         * @param status            the posting decision
         * @param dailyTransaction  the validated staging row
         * @param resolvedAccountId the resolved account id (non-null only when accepted)
         * @param validationCode    the validation code (non-null only when rejected)
         * @param rejectionReason   the exact COBOL reason (non-null only when rejected)
         */
        private ProcessingResult(Status status, DailyTransaction dailyTransaction,
                                 Long resolvedAccountId, Short validationCode, String rejectionReason) {
            this.status = status;
            this.dailyTransaction = dailyTransaction;
            this.resolvedAccountId = resolvedAccountId;
            this.validationCode = validationCode;
            this.rejectionReason = rejectionReason;
        }

        /**
         * Builds an {@link Status#ACCEPTED ACCEPTED} result for a transaction that passed every check.
         *
         * @param dailyTransaction the validated staging row (must not be {@code null})
         * @param accountId        the resolved {@code XREF-ACCT-ID} the writer will post against
         * @return an accepted result with no validation code or rejection reason
         */
        public static ProcessingResult accepted(DailyTransaction dailyTransaction, Long accountId) {
            return new ProcessingResult(Status.ACCEPTED, dailyTransaction, accountId, null, null);
        }

        /**
         * Builds a {@link Status#REJECTED REJECTED} result carrying the COBOL validation code and
         * its exact reason text.
         *
         * @param dailyTransaction the validated staging row (must not be {@code null})
         * @param code             the validation code (100/101/102/103)
         * @param reason           the exact COBOL reason message
         * @return a rejected result with no resolved account id
         */
        public static ProcessingResult rejected(DailyTransaction dailyTransaction, Short code, String reason) {
            return new ProcessingResult(Status.REJECTED, dailyTransaction, null, code, reason);
        }

        /**
         * @return the posting decision ({@link Status#ACCEPTED} or {@link Status#REJECTED})
         */
        public Status getStatus() {
            return status;
        }

        /**
         * @return the daily-transaction staging row that was validated (never {@code null} for a
         *         result produced by the factory methods)
         */
        public DailyTransaction getDailyTransaction() {
            return dailyTransaction;
        }

        /**
         * @return the resolved {@code XREF-ACCT-ID} when {@link #getStatus()} is
         *         {@link Status#ACCEPTED}; {@code null} when rejected
         */
        public Long getResolvedAccountId() {
            return resolvedAccountId;
        }

        /**
         * @return the validation code (100/101/102/103) when {@link #getStatus()} is
         *         {@link Status#REJECTED}; {@code null} when accepted
         */
        public Short getValidationCode() {
            return validationCode;
        }

        /**
         * @return the exact COBOL reason message when {@link #getStatus()} is
         *         {@link Status#REJECTED}; {@code null} when accepted
         */
        public String getRejectionReason() {
            return rejectionReason;
        }
    }
}
