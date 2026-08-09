package com.carddemo.ledger.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Each of the three stores holds ten integer digits and drops any digit past the tenth. No
 * {@code ADD} in the paragraph carries an {@code ON SIZE ERROR} phrase and the phrase appears
 * nowhere in {@code app/cbl}, so a sum wider than the field keeps its low-order ten digits and its
 * sign. A dropped digit is reported once at warning level, without the figure.
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

    /** Reports a dropped high-order digit, and nothing else. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountBalanceUpdater.class);

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
     * <p>The read takes a write lock on the row and the caller's commit releases it, so a second
     * event for the same account waits and then reads the balance this call stored. Without that
     * lock two events read one balance and the second store overwrites the first amount.
     *
     * <p>The stored replica provenance is carried forward unchanged. A posting is a delta this
     * service owns rather than a state the account service published, so it advances no ordering
     * value; clearing the two columns instead would let a change
     * {@code messaging/AccountStateChangedConsumer} had already applied apply a second time.
     *
     * @param accountId the eleven-digit account identifier, padded as {@code ACCT-ID PIC 9(11)} at
     *                  {@code app/cpy/CVACT01Y.cpy:L5} holds it
     * @param amount    the posted amount, from {@code DALYTRAN-AMT PIC S9(09)V99} at
     *                  {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the balance after the add at {@code app/cbl/CBTRN02C.cbl:L547}
     * @throws AccountBalanceRowMissingException when no row carries {@code accountId}
     */
    public BigDecimal updateBalances(String accountId, BigDecimal amount) {
        AccountBalanceProjectionEntity stored = accountBalances.findForUpdateById(accountId)
                .orElseThrow(() -> new AccountBalanceRowMissingException(accountId));

        BigDecimal postedBalance = storedInPictureField(
                CobolDecimal.add(stored.getCurrentBalance(), amount,
                        PicClause.ACCT_CURR_BAL_SCALE),
                PicClause.ACCT_CURR_BAL_PRECISION, PicClause.ACCT_CURR_BAL_SCALE);
        BigDecimal postedCycleCredit = stored.getCycleCredit();
        BigDecimal postedCycleDebit = stored.getCycleDebit();
        if (amount.signum() >= 0) {
            postedCycleCredit = storedInPictureField(
                    CobolDecimal.add(postedCycleCredit, amount,
                            PicClause.ACCT_CURR_CYC_CREDIT_SCALE),
                    PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
                    PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        } else {
            postedCycleDebit = storedInPictureField(
                    CobolDecimal.add(postedCycleDebit, amount,
                            PicClause.ACCT_CURR_CYC_DEBIT_SCALE),
                    PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
                    PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
        }

        accountBalances.save(new AccountBalanceProjectionEntity(stored.getAccountId(),
                postedBalance, postedCycleCredit, postedCycleDebit, stored.getSourceEventId(),
                stored.getSourceOccurredAt()));
        return postedBalance;
    }

    /**
     * Stores one sum in the {@code PIC S9(10)V99} field that holds it.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L547}, {@code :L549} and {@code :L551} each add without an
     * {@code ON SIZE ERROR} phrase, so a sum wider than the field keeps its low-order ten integer
     * digits and its sign. A store that dropped a digit is reported once, naming the capacity and
     * withholding the figure.
     *
     * @param sum       the value one add produced, at the scale of the field it belongs to
     * @param precision the digits the field holds in total, from {@code PicClause}
     * @param scale     the fractional digits the field holds, from {@code PicClause}
     * @return the value the field holds, which is the sum itself when every digit fits
     */
    private static BigDecimal storedInPictureField(BigDecimal sum, int precision, int scale) {
        BigDecimal stored = CobolDecimal.truncateToPictureField(sum, precision, scale);
        if (stored.compareTo(sum) != 0) {
            LOG.warn("A posted figure needed more than the {} integer digits the account balance"
                            + " fields hold at app/cpy/CVACT01Y.cpy:L7, :L13 and :L14, so the"
                            + " high-order digits were dropped where the source ADD statements at"
                            + " app/cbl/CBTRN02C.cbl:L547-L551 drop them. The stored figure and the"
                            + " posted event agree, and both report less than the postings sum to."
                            + " See docs/business-rule-flags.md.",
                    precision - scale);
        }
        return stored;
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

        /** The one message this exception carries, holding no identifier of any kind. */
        static final String MESSAGE =
                "account_balance_projection holds no row for the account this event names";

        /**
         * States that the balance row is absent, and withholds which account it was.
         *
         * <p>An exception message reaches a log, a stack trace and a dead-letter record. An account
         * identifier is stable and reaches every one of those three the moment it appears here, so
         * the message names none. {@code messaging/DeadLetterMetadata} carries the event identifier
         * that correlates the failure back to its delivery.
         *
         * @param accountId the account identifier that matched no row, read for its shape alone and
         *                  never rendered
         * @throws IllegalArgumentException when {@code accountId} is {@code null} or blank, which
         *                                  would mean the posting path lost the key before the read
         */
        public AccountBalanceRowMissingException(String accountId) {
            super(MESSAGE);
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("accountId must be present at the failing read");
            }
        }
    }
}
