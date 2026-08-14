package com.carddemo.authorization.domain;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Makes an approval visible to the next decision for the same account.
 *
 * <p>The gap this closes is one of timing, not of arithmetic. {@code app/cbl/CBTRN02C.cbl} posts each
 * record before it validates the next: paragraph {@code 2000-POST-TRANSACTION} at
 * {@code app/cbl/CBTRN02C.cbl:L424-L444} runs inside the sequential read loop, and paragraph
 * {@code 2700-UPDATE-ACCOUNT} at {@code app/cbl/CBTRN02C.cbl:L545-L560} has already moved
 * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} by the time
 * {@code app/cbl/CBTRN02C.cbl:L403-L405} reads them for the following record. The overlimit test at
 * {@code app/cbl/CBTRN02C.cbl:L407} therefore sees every earlier approval of the run, and two
 * transactions of 60.00 against a limit of 100.00 approve once and decline once.
 *
 * <p>In this platform the account service owns those accumulators, and it reports them back through
 * {@code account.state-changed} four asynchronous hops after the decision: authorize, publish, post,
 * apply. Between the decision and that report {@code account_credit_snapshot} still holds the exposure
 * the account carried before this service approved, so both 60.00 calls approved and the limit was
 * breached by every call arriving inside the window. The replica-freshness refusal in
 * {@link AuthorizationService} cannot see this. The row is not stale: it is current, and it simply does
 * not yet include a decision this service has just taken itself.
 *
 * <p>This component holds the difference. {@link #reservedCycleCredit(AccountCreditSnapshotEntity)}
 * and {@link #reservedCycleDebit(AccountCreditSnapshotEntity)} report the exposure already approved
 * and not yet reported back, which {@code domain/rules/CreditLimitRule} adds to the authoritative
 * figures before it computes the working balance.
 * {@link #reserve(AccountCreditSnapshotEntity, BigDecimal)} records one more approval.
 *
 * <p>Three properties of the source survive untouched, and they are the reason nothing here computes
 * anything the source does not:</p>
 *
 * <ul>
 *   <li>Truncation toward zero. Every accumulation runs through {@link CobolDecimal}, which pins it.
 *       The {@code ROUNDED} phrase appears nowhere in {@code app/cbl/}.</li>
 *   <li>The sign convention of {@code app/cbl/CBTRN02C.cbl:L548-L551}. An amount of zero or more
 *       accumulates into the credit figure and a negative amount into the debit figure, making that
 *       figure more negative. {@code app/cbl/CBTRN02C.cbl:L404} then subtracts it, so a refund raises
 *       the working balance and tightens the next authorization. That is a flagged source defect and
 *       it is reproduced rather than corrected:
 *       {@code card-platform/docs/business-rule-flags.md}.</li>
 *   <li>The narrower {@code WS-TEMP-BAL PIC S9(09)V99} working field at
 *       {@code app/cbl/CBTRN02C.cbl:L187}. It belongs to the rule, which is the one place the source
 *       stores into it, and nothing here narrows anything.</li>
 * </ul>
 *
 * <p>Serialization comes from the lock, not from this class.
 * {@code AccountCreditSnapshotRepository#findForUpdateByAccountId} holds the row for the whole
 * decision, so the figures read here cannot move between the comparison and the write.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class CycleExposureReservation {

    /** Store of the projection this component reads and reserves against. */
    private final AccountCreditSnapshotRepository snapshots;

    /** How long one reservation counts before it is treated as never having been posted. */
    private final Duration reservationTtl;

    /** The lock-wait bound as a PostgreSQL interval string, built once at construction. */
    private final String lockWaitBound;

    /** Supplies the moment a reservation is written and the moment an expiry is measured against. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the store and the reservation lifetime.
     *
     * @param snapshots  store of the account credit projection
     * @param properties the validated service configuration, carrying the reservation lifetime
     * @throws NullPointerException     when an argument or the decision block is {@code null}
     * @throws IllegalArgumentException when the configured lifetime is not positive
     */
    public CycleExposureReservation(AccountCreditSnapshotRepository snapshots,
            AuthorizationProperties properties) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must be present");

        AuthorizationProperties.Decision decision = Objects.requireNonNull(
                Objects.requireNonNull(properties, "properties must be present").decision(),
                "properties.decision must be present");
        this.reservationTtl = Objects.requireNonNull(decision.reservationTtl(),
                "properties.decision.reservationTtl must be present");

        if (reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("reservationTtl must be positive, found "
                    + reservationTtl);
        }
        if (decision.lockWaitMs() <= 0) {
            throw new IllegalArgumentException("lockWaitMs must be positive, found "
                    + decision.lockWaitMs());
        }
        this.lockWaitBound = decision.lockWaitMs() + "ms";
    }

    /**
     * Bounds how long this transaction's locked read waits for an account another decision holds.
     *
     * <p>Called once at the head of a decision, before any rule runs. PostgreSQL waits forever by
     * default, so without this a stalled decision would stall every later decision for the same account
     * with no upper bound and the request would stay open for as long as the other one took.
     *
     * <p>The bound is transaction-local, so it governs this decision alone and never a schema migration
     * or the relay sweep. A migration that gave up on a lock would leave a half-applied schema.
     *
     * @throws org.springframework.dao.DataAccessException when the bound could not be applied, which
     *                                                     leaves the decision unable to proceed safely
     */
    public void boundLockWait() {
        snapshots.applyLockWaitBound(lockWaitBound);
    }

    /**
     * Returns the approved cycle credit that has not yet been reported back for one account.
     *
     * @param account the projection row this decision read under lock
     * @return the reserved figure while its expiry stands, and zero once it has passed
     * @throws NullPointerException when {@code account} is {@code null}
     */
    public BigDecimal reservedCycleCredit(AccountCreditSnapshotEntity account) {
        return Objects.requireNonNull(account, "account must be present")
                .effectivePendingCycleCredit(clock.instant());
    }

    /**
     * Returns the approved cycle debit that has not yet been reported back for one account.
     *
     * @param account the projection row this decision read under lock
     * @return the reserved figure while its expiry stands, and zero once it has passed
     * @throws NullPointerException when {@code account} is {@code null}
     */
    public BigDecimal reservedCycleDebit(AccountCreditSnapshotEntity account) {
        return Objects.requireNonNull(account, "account must be present")
                .effectivePendingCycleDebit(clock.instant());
    }

    /**
     * Records the exposure one approval has committed, so the next decision for the same account sees
     * it.
     *
     * <p>Runs inside the decision transaction, after every rule has accepted and beside the decision
     * row and the outbox row. All three commit together, so an approval that reached a consumer without
     * reserving its exposure cannot exist and neither can a reservation for an approval that rolled
     * back.
     *
     * <p>The figures written are the effective ones plus this amount, not the stored ones plus this
     * amount. That is what retires an expired reservation: an expired figure reads as zero, so the
     * write replaces it rather than adding to it, and no sweep is needed to clear the table.
     *
     * <p>A fresh expiry is written on every reservation. An account authorizing steadily therefore
     * keeps its outstanding exposure counted for as long as it keeps authorizing, and an account whose
     * approvals stopped arriving releases its exposure once the lifetime has passed.
     *
     * <p>Each sum is stored at the width the accumulator it mirrors holds, which is
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} and {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L12-L13} and columns {@code pending_cycle_credit} and
     * {@code pending_cycle_debit} at {@code NUMERIC(12,2)}. Ten integer digits hold at most two
     * approvals of the widest {@code DALYTRAN-AMT PIC S9(09)V99} amount before an eleventh digit
     * would be needed, and a COBOL {@code ADD} with no {@code ON SIZE ERROR} phrase discards that
     * digit where it stands rather than failing.
     * {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)} performs that store, so a
     * long run of maximum approvals inside one reservation lifetime reaches the same figure the
     * accumulator itself would hold and never the datastore's own overflow.
     *
     * @param account the projection row this decision read under lock
     * @param amount  the approved amount at scale 2, either sign
     * @throws NullPointerException  when either argument is {@code null}
     * @throws IllegalStateException when no row carries the account identifier, which means the row
     *                               vanished after this transaction locked it
     */
    public void reserve(AccountCreditSnapshotEntity account, BigDecimal amount) {
        Objects.requireNonNull(account, "account must be present");
        Objects.requireNonNull(amount, "amount must be present");

        Instant now = clock.instant();
        BigDecimal reservedCredit = account.effectivePendingCycleCredit(now);
        BigDecimal reservedDebit = account.effectivePendingCycleDebit(now);

        if (amount.signum() >= 0) {
            reservedCredit = CobolDecimal.truncateToPictureField(
                    CobolDecimal.add(reservedCredit, amount,
                            PicClause.ACCT_CURR_CYC_CREDIT_SCALE),
                    PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
                    PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        } else {
            reservedDebit = CobolDecimal.truncateToPictureField(
                    CobolDecimal.add(reservedDebit, amount,
                            PicClause.ACCT_CURR_CYC_DEBIT_SCALE),
                    PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
                    PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
        }

        int written = snapshots.reserveCycleExposure(account.getAccountId(), reservedCredit,
                reservedDebit, now.plus(reservationTtl));

        if (written == AccountCreditSnapshotRepository.NO_ROW_WRITTEN) {
            throw new IllegalStateException("no account credit snapshot row carries the account this "
                    + "decision locked, so the approved exposure could not be reserved");
        }
    }
}
