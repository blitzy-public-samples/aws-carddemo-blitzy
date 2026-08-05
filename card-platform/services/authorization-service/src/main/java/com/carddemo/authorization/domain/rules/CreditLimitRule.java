package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tests the transaction against the credit limit, and assigns reject code {@code 0102} when it does
 * not fit.
 *
 * <p>Transformed from {@code app/cbl/CBTRN02C.cbl:L403-L413}. The source computes
 * {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} at
 * {@code app/cbl/CBTRN02C.cbl:L403-L405}, then approves only while
 * {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:L407}.
 *
 * <p>Three properties of that computation are reproduced deliberately.
 *
 * <p>First, the current balance takes no part. {@code ACCT-CURR-BAL} at
 * {@code app/cpy/CVACT01Y.cpy:L7} is never read here, so the test works from the two cycle
 * accumulators alone.
 *
 * <p>Second, the working field is narrower than its operands.
 * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187} holds nine digits before
 * the decimal point, while {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} at
 * {@code app/cpy/CVACT01Y.cpy:L13-L14} each hold ten, as does the credit limit at
 * {@code app/cpy/CVACT01Y.cpy:L8}. A computed value reaching one billion loses its high-order digit
 * on the store, and because the comparison approves on greater-or-equal, that loss turns a decline
 * into an approval. {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)} reproduces the
 * store at {@link PicClause#WS_TEMP_BAL_PRECISION} and {@link PicClause#WS_TEMP_BAL_SCALE}.
 *
 * <p>Third, a refund tightens the next authorization. A negative amount reaches the cycle-debit
 * accumulator at {@code app/cbl/CBTRN02C.cbl:L551}, making it more negative, and this formula
 * subtracts that accumulator. The tested value therefore rises after a refund.
 *
 * <p>Every arithmetic step truncates toward zero. The {@code ROUNDED} phrase appears in none of the
 * programs under {@code app/cbl}, so {@link CobolDecimal} pins truncation at each step.
 *
 * <p>This rule does not stop the chain. {@code app/cbl/CBTRN02C.cbl:L407-L420} holds two sequential
 * tests with no gate between them, so {@link AccountExpirationRule} runs even after this rule
 * declines.
 */
@Component
@Order(102)
public class CreditLimitRule implements DeclineRule {

    /**
     * Tests the amount against the credit limit through the narrowed working field.
     *
     * @param context values for one authorization call, carrying the resolved account snapshot
     * @return {@link DeclineReason#OVER_CREDIT_LIMIT} when the credit limit is below the tested
     *         value, and an empty result when it is not
     * @throws IllegalStateException when no account snapshot reached the context, which means the
     *                               chain ran this rule before the account resolved
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        AccountCreditSnapshotEntity account = context.getAccountCreditSnapshot();
        if (account == null) {
            throw new IllegalStateException("the credit test follows the account read, and no "
                    + "account snapshot reached this call");
        }

        BigDecimal cycleDifference = CobolDecimal.subtract(account.getCurrentCycleCredit(),
                account.getCurrentCycleDebit(), PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed = CobolDecimal.add(cycleDifference, context.getAmount(),
                PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal storedInWorkingField = CobolDecimal.truncateToPictureField(computed,
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

        if (account.getCreditLimit().compareTo(storedInWorkingField) >= 0) {
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
