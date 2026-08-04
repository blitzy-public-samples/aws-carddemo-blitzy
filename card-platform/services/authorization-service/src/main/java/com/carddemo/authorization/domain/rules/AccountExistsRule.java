package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.events.DeclineReason;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads the account the card resolved to, and assigns reject code {@code 0101} when none exists.
 *
 * <p>Transformed from the opening of paragraph {@code 1500-B-LOOKUP-ACCT} at
 * {@code app/cbl/CBTRN02C.cbl:L393-L399}. {@code app/cbl/CBTRN02C.cbl:L394} moves the account
 * identifier from the cross-reference record into the key field,
 * {@code app/cbl/CBTRN02C.cbl:L395} reads the account dataset, and the {@code INVALID KEY} branch at
 * {@code app/cbl/CBTRN02C.cbl:L397-L399} assigns the code and its text.
 *
 * <p>The identifier comes from the row {@link CardCrossReferenceRule} resolved, never from the
 * request. A caller that names an account cannot skip the cross-reference read, because the source
 * reads that record first and takes the identifier from it.
 *
 * <p>The resolved snapshot reaches {@link DeclineRule.Context}, so {@link CreditLimitRule} and
 * {@link AccountExpirationRule} read the values this one read. That mirrors
 * {@code ACCOUNT-RECORD}, the working-storage area {@code app/cbl/CBTRN02C.cbl:L395} reads into.
 *
 * <p>This rule stops the chain when it declines. The three tests that follow read an account, and
 * {@code app/cbl/CBTRN02C.cbl:L396} reaches them only on its {@code NOT INVALID KEY} branch.
 */
@Component
@Order(101)
public class AccountExistsRule implements DeclineRule {

    /** Reads {@code account_credit_snapshot} on the account identifier. */
    private final AccountCreditSnapshotRepository accountCreditSnapshots;

    /**
     * Takes the repository this rule reads.
     *
     * @param accountCreditSnapshots reader of the account credit snapshot table
     */
    public AccountExistsRule(AccountCreditSnapshotRepository accountCreditSnapshots) {
        this.accountCreditSnapshots = accountCreditSnapshots;
    }

    /**
     * Reads the account row the cross-reference named, and declines when none carries it.
     *
     * @param context values for one authorization call, carrying the resolved cross-reference row
     * @return {@link DeclineReason#ACCOUNT_NOT_FOUND} when no row carries the identifier, and an
     *         empty result when one does
     * @throws IllegalStateException when no cross-reference row reached the context, which means the
     *                               chain ran this rule before the card resolved
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        if (context.getCardCrossReference() == null) {
            throw new IllegalStateException("the account read follows the cross-reference read, and "
                    + "no cross-reference row reached this call");
        }
        Optional<AccountCreditSnapshotEntity> resolved = accountCreditSnapshots
                .findByAccountId(context.getCardCrossReference().getAccountId());
        if (resolved.isEmpty()) {
            return Optional.of(DeclineReason.ACCOUNT_NOT_FOUND);
        }
        context.setAccountCreditSnapshot(resolved.get());
        return Optional.empty();
    }

    /**
     * Declares that the chain stops here when this rule declines.
     *
     * @return {@link DeclineRule.Segment#STOP_ON_FIRST_DECLINE}
     */
    @Override
    public Segment segment() {
        return Segment.STOP_ON_FIRST_DECLINE;
    }
}
