package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.events.DeclineReason;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads the account the card resolved to, and assigns reject code {@code 0101} when no row carries
 * it.
 *
 * <p>Transformed from the opening of paragraph {@code 1500-B-LOOKUP-ACCT} at
 * {@code app/cbl/CBTRN02C.cbl:L393-L399}. {@code app/cbl/CBTRN02C.cbl:L394} moves the account
 * identifier from the cross-reference record into the key field,
 * {@code app/cbl/CBTRN02C.cbl:L395} reads the account dataset, and the {@code INVALID KEY} branch
 * at {@code app/cbl/CBTRN02C.cbl:L397-L399} assigns the code and its text.
 *
 * <p>The identifier arrives on the row {@link CardCrossReferenceRule} resolved, and never from the
 * request. The identifier holds the eleven digit characters {@code XREF-ACCT-ID PIC 9(11)} carries
 * at {@code app/cpy/CVACT03Y.cpy:L7}, which is the width {@code KEYS(11 0)} declares at
 * {@code app/jcl/ACCTFILE.jcl:L40}. The lookup passes those characters through untouched, leading
 * zeros and all.
 *
 * <p>A resolved row reaches {@link DeclineRule.Context}, which mirrors {@code ACCOUNT-RECORD}, the
 * working-storage area {@code app/cbl/CBTRN02C.cbl:L395} reads into. {@link CreditLimitRule} and
 * {@link AccountExpirationRule} read the credit and expiry values from that row, as the two tests
 * inside the {@code NOT INVALID KEY} branch at {@code app/cbl/CBTRN02C.cbl:L403-L420} do.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
@Order(20)
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
     * Reads the account row the cross-reference named, and declines when no row carries it.
     *
     * <p>A resolved row reaches {@code context}, for the rules that follow.
     *
     * <p>The read holds the row for the rest of the decision, which is why it is
     * {@link AccountCreditSnapshotRepository#findForUpdateByAccountId(String)} and not the plain
     * lookup. Two concurrent calls for one account would otherwise both read the cycle accumulators
     * before either reserved anything, and both would approve against the same exposure. The lock has
     * to be taken here rather than at the reservation write, because it must cover the figures
     * {@link CreditLimitRule} computes from as well as the write itself. How long a call waits for it
     * is bounded by {@code domain/AuthorizationService}, which applies a transaction-local
     * {@code lock_timeout} before the chain runs.
     *
     * <p>A miss locks nothing and declines, exactly as the unlocked read did. Two calls naming one
     * absent account therefore both decline, and neither has any exposure to reserve.
     *
     * @param context values for one authorization call, carrying the resolved cross-reference row
     * @return {@link DeclineReason#ACCOUNT_NOT_FOUND} when no row carries the identifier, and an
     *         empty result when one does
     * @throws NullPointerException when no cross-reference row reached {@code context}, which means
     *                              the chain ran this rule before the card resolved
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        CardCrossReferenceEntity resolvedCard = Objects.requireNonNull(
                context.getCardCrossReference(),
                "the account read follows the cross-reference read, and no cross-reference row "
                        + "reached this call");
        Optional<AccountCreditSnapshotEntity> resolved =
                accountCreditSnapshots.findForUpdateByAccountId(resolvedCard.getAccountId());
        resolved.ifPresent(context::setAccountCreditSnapshot);
        if (resolved.isEmpty()) {
            return Optional.of(DeclineReason.ACCOUNT_NOT_FOUND);
        }
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
