package com.carddemo.account.domain;

import com.carddemo.account.domain.validation.EditResult;
import java.util.Objects;

/**
 * What one account update answered: the verdict, and the account as it stood when the transaction
 * ended.
 *
 * <p>{@link AccountUpdateService#updateAccount} used to answer with the verdict alone, which left
 * its caller no way to report the row except to read it again. The snapshot travels with the verdict
 * so that second read is not needed.
 *
 * <p>{@link #account()} is present on every verdict that describes a row this call resolved, which is
 * every passing verdict — the one that wrote both records and the one that found nothing to change.
 * It is absent on a failing verdict, because a refused field, a lost race and a missing relationship
 * each leave the caller with a message and no row to report.
 *
 * @param verdict what the edits and the write decided, carrying one message
 * @param account the eleven account values as the transaction left them, or {@code null} where the
 *                call resolved no row
 */
public record AccountUpdateOutcome(EditResult verdict, AccountSnapshot account) {

    /**
     * Checks the verdict is present.
     *
     * @throws NullPointerException if {@code verdict} is {@code null}
     */
    public AccountUpdateOutcome {
        Objects.requireNonNull(verdict, "verdict must be present");
    }

    /**
     * Answers a verdict that resolved no row.
     *
     * @param verdict the verdict to carry
     * @return that verdict with no snapshot
     */
    public static AccountUpdateOutcome of(EditResult verdict) {
        return new AccountUpdateOutcome(verdict, null);
    }
}
