package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tests the capture date against the account expiry date, and assigns reject code {@code 0103} when
 * the transaction arrived after it.
 *
 * <p>Transformed from {@code app/cbl/CBTRN02C.cbl:L414-L420}. The source approves only while
 * {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}.
 *
 * <p>The comparison runs on text, and that is deliberate. {@code ACCT-EXPIRAION-DATE PIC X(10)} at
 * {@code app/cpy/CVACT01Y.cpy:L11} holds characters, and the reference form is the first ten
 * characters of {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}. Both are
 * shaped year, month then day, so their character order matches their calendar order. Converting
 * either side to a date type would read more naturally and would change the outcome for any value
 * that is not a well-formed date, which is why the column stays {@code VARCHAR(10)}.
 *
 * <p>The source field name carries a transposed word. The target spells it
 * {@code accountExpirationDate}.
 *
 * <p>This rule runs last of the four and its answer wins. {@code app/cbl/CBTRN02C.cbl:L407-L420}
 * holds no gate between the credit test and this one, so a transaction failing both carries this
 * reject code and the earlier assignment at {@code app/cbl/CBTRN02C.cbl:L410} does not survive.
 */
@Component
@Order(103)
public class AccountExpirationRule implements DeclineRule {

    /**
     * Compares the account expiry date against the first ten characters of the capture timestamp.
     *
     * @param context values for one authorization call, carrying the resolved account snapshot
     * @return {@link DeclineReason#ACCOUNT_EXPIRED} when the expiry date sorts below the capture
     *         date, and an empty result when it does not
     * @throws IllegalStateException when no account snapshot reached the context, or when the capture
     *                               timestamp holds fewer than
     *                               {@link PicClause#ACCT_EXPIRATION_DATE_WIDTH} characters
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        AccountCreditSnapshotEntity account = context.getAccountCreditSnapshot();
        if (account == null) {
            throw new IllegalStateException("the expiry test follows the account read, and no "
                    + "account snapshot reached this call");
        }
        String originTimestamp = context.getOriginTimestamp();
        if (originTimestamp.length() < PicClause.ACCT_EXPIRATION_DATE_WIDTH) {
            throw new IllegalStateException("the expiry test reads the first "
                    + PicClause.ACCT_EXPIRATION_DATE_WIDTH
                    + " characters of the capture timestamp, and the supplied value holds "
                    + originTimestamp.length());
        }

        String capturedDate =
                originTimestamp.substring(0, PicClause.ACCT_EXPIRATION_DATE_WIDTH);
        if (account.getAccountExpirationDate().compareTo(capturedDate) >= 0) {
            return Optional.empty();
        }
        return Optional.of(DeclineReason.ACCOUNT_EXPIRED);
    }

    /**
     * Declares that this rule belongs to the segment whose last decline wins.
     *
     * @return {@link DeclineRule.Segment#LAST_DECLINE_WINS}
     */
    @Override
    public Segment segment() {
        return Segment.LAST_DECLINE_WINS;
    }
}
