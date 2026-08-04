package com.carddemo.ledger.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Adds one posted amount to an account's balance, then to one of its two billing-cycle
 * accumulators.
 *
 * <p>Reproduces {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560}, the
 * second of the three updates {@code app/cbl/CBTRN02C.cbl:L440-L442} runs in a fixed order.
 * {@code L547} adds the amount to {@code ACCT-CURR-BAL}. {@code L548} then routes the same amount
 * by sign: zero or above to {@code ACCT-CURR-CYC-CREDIT} at {@code L549}, negative to
 * {@code ACCT-CURR-CYC-DEBIT} at {@code L551}. One accumulator changes per call and the other
 * keeps its stored value. All three fields are {@code PIC S9(10)V99}, at
 * {@code app/cpy/CVACT01Y.cpy:L7}, {@code :L13} and {@code :L14}.
 *
 * <p>The {@code REWRITE} at {@code app/cbl/CBTRN02C.cbl:L554} becomes a save of a replacement row.
 * Alone among that program's writes, this paragraph tests no file status and calls no abend
 * routine. A missing row throws {@link AccountBalanceRowMissingException}.
 *
 * <p>Deviations and flagged findings for this class are recorded in
 * {@code card-platform/docs/decision-log.md} and
 * {@code card-platform/docs/business-rule-flags.md}.
 */
@Service
public class AccountBalanceUpdater {

    /** Reads one balance row by account identifier and saves the replacement row. */
    private final AccountBalanceProjectionRepository accountBalances;

    /**
     * Takes the store of balance rows.
     *
     * @param accountBalances repository over {@code account_balance_projection}
     */
    public AccountBalanceUpdater(AccountBalanceProjectionRepository accountBalances) {
        this.accountBalances = accountBalances;
    }

    /**
     * Adds {@code amount} to the stored balance and to one cycle accumulator, then saves the row.
     *
     * @param accountId the eleven-digit account identifier, padded as {@code ACCT-ID PIC 9(11)} at
     *                  {@code app/cpy/CVACT01Y.cpy:L5} holds it
     * @param amount    the posted amount, from {@code DALYTRAN-AMT PIC S9(09)V99} at
     *                  {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the balance after the add at {@code app/cbl/CBTRN02C.cbl:L547}
     * @throws AccountBalanceRowMissingException when no row carries {@code accountId}
     */
    public BigDecimal updateBalances(String accountId, BigDecimal amount) {
        AccountBalanceProjectionEntity stored = accountBalances.findById(accountId)
                .orElseThrow(() -> new AccountBalanceRowMissingException(accountId));

        BigDecimal postedBalance = CobolDecimal.add(stored.getCurrentBalance(), amount,
                PicClause.ACCT_CURR_BAL_SCALE);
        BigDecimal postedCycleCredit = stored.getCycleCredit();
        BigDecimal postedCycleDebit = stored.getCycleDebit();
        if (amount.signum() >= 0) {
            postedCycleCredit = CobolDecimal.add(postedCycleCredit, amount,
                    PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        } else {
            postedCycleDebit = CobolDecimal.add(postedCycleDebit, amount,
                    PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
        }

        accountBalances.save(new AccountBalanceProjectionEntity(stored.getAccountId(),
                postedBalance, postedCycleCredit, postedCycleDebit));
        return postedBalance;
    }

    /**
     * Signals that {@code account_balance_projection} carries no row for the account being posted.
     *
     * <p>The target form of the {@code INVALID KEY} branch at
     * {@code app/cbl/CBTRN02C.cbl:L555-L558}, which reports {@code 'ACCOUNT RECORD NOT FOUND'}.
     * Being unchecked, it rolls back the caller's transaction, leaves the message unacknowledged,
     * and lets redelivery and then the dead-letter topic carry it.
     */
    public static final class AccountBalanceRowMissingException extends RuntimeException {

        /** Serialization identity of this exception type. */
        private static final long serialVersionUID = 1L;

        /**
         * Names the account whose balance row is absent.
         *
         * @param accountId the account identifier that matched no row
         */
        public AccountBalanceRowMissingException(String accountId) {
            super("no account_balance_projection row for account " + accountId);
        }
    }
}
