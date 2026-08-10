package com.carddemo.account.domain;

import com.carddemo.account.entity.AccountEntity;
import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The eleven account values one call committed, read off the row inside the transaction that wrote
 * it.
 *
 * <p>An update used to answer by reading the account again after its transaction had committed: one
 * more statement, one more row, and a second answer to a question the transaction had already
 * answered. The values it wrote were in hand when it committed them, so this record carries them out
 * instead.
 *
 * <p>It is a record rather than an {@link AccountEntity}, and that is the point. An entity handed
 * back from a transaction is either managed — in which case a reader outside the transaction can
 * trip a lazy load or, worse, mutate a row by touching a setter — or detached and
 * indistinguishable from the managed kind at the call site. These eleven values are neither: they
 * are final, they belong to no persistence context, and nothing can be loaded through them.
 *
 * <p>The field set is the field set of the account read, taken from {@code 1200-SETUP-SCREEN-VARS}
 * at {@code app/cbl/COACTVWC.cbl:L460-L537}. {@code ACCT-ADDR-ZIP} at
 * {@code app/cpy/CVACT01Y.cpy:L15} is absent here because it is absent from the read, and the
 * omission is recorded in {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>This record is the single extraction point from a row to those eleven values. The read path and
 * the update path both answer through it, so the two cannot come to describe an account differently.
 *
 * @param accountId          the account this snapshot describes, eleven digits of text
 * @param activeStatus       the stored active-status letter
 * @param currentBalance     the balance as committed
 * @param creditLimit        the credit limit as committed
 * @param cashCreditLimit    the cash credit limit as committed
 * @param currentCycleCredit the cycle credit accumulator as committed
 * @param currentCycleDebit  the cycle debit accumulator as committed
 * @param openDate           the stored open date, as text
 * @param expirationDate     the stored expiry date, as text, because the authorization rule
 *                           compares it as text
 * @param reissueDate        the stored reissue date, as text
 * @param groupId            the stored disclosure group identifier
 */
public record AccountSnapshot(
        String accountId,
        String activeStatus,
        BigDecimal currentBalance,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        BigDecimal currentCycleCredit,
        BigDecimal currentCycleDebit,
        String openDate,
        String expirationDate,
        String reissueDate,
        String groupId) {

    /**
     * Reads the eleven values off one row.
     *
     * <p>Call this while the row is still readable — inside the transaction that wrote it. Every
     * component is copied by value, so the snapshot it returns outlives the persistence context the
     * row belonged to.
     *
     * @param account the row to read, managed or not
     * @return the eleven values that row holds
     * @throws NullPointerException if {@code account} is {@code null}
     */
    public static AccountSnapshot of(AccountEntity account) {
        Objects.requireNonNull(account, "account must not be null");
        return new AccountSnapshot(account.getAccountId(), account.getActiveStatus(),
                account.getCurrentBalance(), account.getCreditLimit(),
                account.getCashCreditLimit(), account.getCurrentCycleCredit(),
                account.getCurrentCycleDebit(), account.getOpenDate(),
                account.getExpirationDate(), account.getReissueDate(), account.getGroupId());
    }

    /**
     * Renders every component as withheld.
     *
     * <p>A generated rendering carries five monetary values and a status of somebody's account. A
     * diagnostic line naming an account does not need them, and a log holding them is a log that has
     * to be protected like the database.
     *
     * @return the class name with {@link EventEnvelope#WITHHELD} in place of every component
     */
    @Override
    public String toString() {
        return "AccountSnapshot[accountId=" + EventEnvelope.WITHHELD + ", activeStatus="
                + EventEnvelope.WITHHELD + ", currentBalance=" + EventEnvelope.WITHHELD
                + ", creditLimit=" + EventEnvelope.WITHHELD + ", cashCreditLimit="
                + EventEnvelope.WITHHELD + ", currentCycleCredit=" + EventEnvelope.WITHHELD
                + ", currentCycleDebit=" + EventEnvelope.WITHHELD + ", openDate="
                + EventEnvelope.WITHHELD + ", expirationDate=" + EventEnvelope.WITHHELD
                + ", reissueDate=" + EventEnvelope.WITHHELD + ", groupId="
                + EventEnvelope.WITHHELD + "]";
    }
}
