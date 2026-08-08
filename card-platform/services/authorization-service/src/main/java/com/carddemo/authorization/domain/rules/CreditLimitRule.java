package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.CycleExposureReservation;
import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tests one transaction against the account credit limit, and assigns reject code {@code 0102} when
 * the limit does not reach the working balance.
 *
 * <p>Transformed from {@code app/cbl/CBTRN02C.cbl:L403-L413}, inside the {@code NOT INVALID KEY}
 * branch of the account read. The source computes the working balance in one statement:
 *
 * <pre>{@code
 * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                     - ACCT-CURR-CYC-DEBIT
 *                     + DALYTRAN-AMT
 * }</pre>
 *
 * <p>The working balance is the cycle credit less the cycle debit plus the transaction amount,
 * stored into a field of nine integer digits at scale two. A limit equal to that balance approves.
 * {@code app/cbl/CBTRN02C.cbl:L407} tests {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}.
 *
 * <p>{@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7} takes no part in the formula, and
 * {@link AccountCreditSnapshotEntity} carries no column for it.
 *
 * <p>The working field is narrower than every value it meets.
 * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187} holds nine integer digits,
 * while {@code ACCT-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy:L8},
 * {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13} and
 * {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14} each hold ten. A working balance
 * reaching one billion loses its high-order digit on the store, and the narrowed value then fits
 * under a limit the full value exceeds.
 *
 * <p>A refund raises the working balance. {@code app/cbl/CBTRN02C.cbl:L551} adds a negative amount
 * to the cycle debit accumulator, and {@code app/cbl/CBTRN02C.cbl:L404} subtracts that accumulator.
 *
 * <p>This rule does not stop the chain. {@code app/cbl/CBTRN02C.cbl:L413} closes its test and
 * {@code app/cbl/CBTRN02C.cbl:L414} opens the expiration test with no gate between them, so
 * {@link AccountExpirationRule} runs even after this rule declines.
 *
 * <h2>What the two accumulators hold here that they do not hold in the source</h2>
 *
 * <p>The source reads accumulators that already carry every earlier approval of the run, because
 * paragraph {@code 2700-UPDATE-ACCOUNT} at {@code app/cbl/CBTRN02C.cbl:L545-L560} rewrites the account
 * record inside the same sequential loop that validates the next record. Two transactions of 60.00
 * against a limit of 100.00 therefore approve once and decline once.
 *
 * <p>Here the account service owns the accumulators and reports them back four asynchronous hops after
 * the decision, so {@code account_credit_snapshot} holds the exposure the account carried before this
 * service approved. {@link CycleExposureReservation} supplies the difference: the exposure already
 * approved and not yet reported. Each figure is added to its authoritative twin before the working
 * balance is computed, so this rule reads the figures the account would carry had every approved
 * transaction already posted, which is what the source reads.
 *
 * <p>Nothing about the computation changes. Both additions run through {@link CobolDecimal} at the
 * accumulator scale, matching the {@code ADD} statements at
 * {@code app/cbl/CBTRN02C.cbl:L549} and {@code :L551} that the ledger service reproduces; the
 * subtraction, the addition of the amount and the one narrowing store below are untouched.
 *
 * <p>Flagged source findings: {@code card-platform/docs/business-rule-flags.md}.
 */
@Component
@Order(30)
public class CreditLimitRule implements DeclineRule {

    /** Supplies the approved exposure the account service has not yet reported back. */
    private final CycleExposureReservation reservation;

    /**
     * Takes the reservation this rule reads.
     *
     * @param reservation supplier of the approved exposure not yet reported back
     * @throws NullPointerException when {@code reservation} is {@code null}
     */
    public CreditLimitRule(CycleExposureReservation reservation) {
        this.reservation = Objects.requireNonNull(reservation, "reservation must be present");
    }

    /**
     * Computes the working balance and declines when the credit limit does not reach it.
     *
     * <p>Three steps carry {@code app/cbl/CBTRN02C.cbl:L403-L407}, over accumulators that carry their
     * reserved exposure. The subtraction and the addition run at
     * {@link PicClause#WS_TEMP_BAL_SCALE} through {@link CobolDecimal}, which truncates
     * toward zero. The single store narrows the result to {@link PicClause#WS_TEMP_BAL_PRECISION}
     * through {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)}. The comparison
     * reads that narrowed value.
     *
     * @param context values for one authorization call, carrying the account snapshot
     *                {@link AccountExistsRule} resolved
     * @return {@link DeclineReason#OVER_CREDIT_LIMIT} when the credit limit falls below the working
     *         balance, and an empty result when it reaches the working balance
     * @throws NullPointerException when no account snapshot reached {@code context}, which means
     *                              the chain ran this rule before the account resolved
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        AccountCreditSnapshotEntity account = Objects.requireNonNull(
                context.getAccountCreditSnapshot(),
                "the credit test follows the account read, and no account snapshot reached this "
                        + "call");

        BigDecimal cycleCredit = CobolDecimal.add(account.getCurrentCycleCredit(),
                reservation.reservedCycleCredit(account),
                PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        BigDecimal cycleDebit = CobolDecimal.add(account.getCurrentCycleDebit(),
                reservation.reservedCycleDebit(account),
                PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

        BigDecimal cycleDifference =
                CobolDecimal.subtract(cycleCredit, cycleDebit, PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed = CobolDecimal.add(cycleDifference, context.getAmount(),
                PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal workingBalance = CobolDecimal.truncateToPictureField(computed,
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

        if (account.getCreditLimit().compareTo(workingBalance) >= 0) {
            return Optional.empty();
        }
        return Optional.of(DeclineReason.OVER_CREDIT_LIMIT);
    }

    /**
     * Declares that a later rule of the same segment can overwrite this rule's answer.
     *
     * @return {@link DeclineRule.Segment#LAST_DECLINE_WINS}
     */
    @Override
    public Segment segment() {
        return Segment.LAST_DECLINE_WINS;
    }
}
