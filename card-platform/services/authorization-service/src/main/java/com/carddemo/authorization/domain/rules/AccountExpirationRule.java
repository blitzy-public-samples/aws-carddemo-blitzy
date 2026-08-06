package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.events.DeclineReason;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tests the transaction capture date against the account expiry date, and assigns reject code
 * {@code 0103} when the transaction arrived after the account expired.
 *
 * <p>Transformed from {@code app/cbl/CBTRN02C.cbl:L414-L420}, the second of two ungated tests
 * inside the {@code NOT INVALID KEY} branch of the account read at
 * {@code app/cbl/CBTRN02C.cbl:L393-L422}:
 *
 * <pre>{@code
 * IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
 *   CONTINUE
 * ELSE
 *   MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * }</pre>
 *
 * <p>Both operands are text and both stay text. {@code ACCT-EXPIRAION-DATE PIC X(10)} at
 * {@code app/cpy/CVACT01Y.cpy:L11} holds ten characters, and {@code (1:10)} takes the leading ten
 * characters of {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}. Each value
 * is shaped year, then month, then day. Sorting those characters therefore sorts the dates, and
 * {@link String#compareTo(String)} reproduces the source ordering. The column is
 * {@code VARCHAR(10)} and the field is a {@link String}.
 *
 * <p>{@code >=} at {@code app/cbl/CBTRN02C.cbl:L414} approves an expiry date equal to the capture
 * date. {@code CONTINUE} at {@code app/cbl/CBTRN02C.cbl:L415} sits on that approving side.
 *
 * <p>The source spells the account field with a transposed word. The target spells it
 * {@code accountExpirationDate}, a rename recorded in
 * {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>This rule's answer overwrites an earlier decline of the same segment.
 * {@code app/cbl/CBTRN02C.cbl:L413} closes the credit test and
 * {@code app/cbl/CBTRN02C.cbl:L414} opens this one with no gate between them.
 */
@Component
@Order(40)
public class AccountExpirationRule implements DeclineRule {

    /**
     * Compares the account expiry date against the leading ten characters of the capture timestamp.
     *
     * <p>The capture timestamp keeps all twenty-six of its characters in {@code context}, and this
     * test reads the first ten of them. The character at position eleven is a space and takes no
     * part in the comparison.
     *
     * @param context values for one authorization call, carrying the account snapshot
     *                {@link AccountExistsRule} resolved
     * @return {@link DeclineReason#ACCOUNT_EXPIRED} when the expiry date sorts below the capture
     *         date, and an empty result when it reaches the capture date
     * @throws NullPointerException when no account snapshot reached {@code context}, which means
     *                              the chain ran this rule before the account resolved
     * @throws IndexOutOfBoundsException when the capture timestamp holds fewer than ten characters,
     *                                   a width the request contract already refuses
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        AccountCreditSnapshotEntity account = Objects.requireNonNull(
                context.getAccountCreditSnapshot(),
                "the expiry test follows the account read, and no account snapshot reached this "
                        + "call");

        String capturedDate = context.getOriginTimestamp().substring(0, 10);
        if (account.getAccountExpirationDate().compareTo(capturedDate) >= 0) {
            return Optional.empty();
        }
        return Optional.of(DeclineReason.ACCOUNT_EXPIRED);
    }

    /**
     * Declares that this rule's answer overwrites an earlier decline of the same segment.
     *
     * @return {@link DeclineRule.Segment#LAST_DECLINE_WINS}
     */
    @Override
    public Segment segment() {
        return Segment.LAST_DECLINE_WINS;
    }
}
