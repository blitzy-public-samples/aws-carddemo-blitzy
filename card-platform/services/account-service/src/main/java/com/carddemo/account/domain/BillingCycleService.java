package com.carddemo.account.domain;

import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.AccountStateChanged.ChangeKind;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes the billing cycle of one account by zeroing both cycle accumulators.
 *
 * <p>Realises two statements of {@code 1050-UPDATE-ACCOUNT.} at
 * {@code app/cbl/CBACT04C.cbl:L350}: {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at
 * {@code app/cbl/CBACT04C.cbl:L353} and {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at
 * {@code app/cbl/CBACT04C.cbl:L354}. The two fields are
 * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13} and
 * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14}. This class
 * reproduces those two statements and no other statement of that paragraph.</p>
 *
 * <p>The two accumulators drive one authorization rule. {@code app/cbl/CBTRN02C.cbl:L403-L405}
 * reads both into {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}, and
 * {@code app/cbl/CBTRN02C.cbl:L407} compares that working balance with the credit limit. A failed
 * comparison assigns reject reason 102 at {@code app/cbl/CBTRN02C.cbl:L410-L411}.</p>
 *
 * <p>The keyed read holds a write lock. The source declares the account file
 * {@code ACCESS MODE IS RANDOM} at {@code app/cbl/CBACT04C.cbl:L43}, opens it
 * {@code OPEN I-O ACCOUNT-FILE} at {@code app/cbl/CBACT04C.cbl:L291} and rewrites the record it
 * holds at {@code app/cbl/CBACT04C.cbl:L356}.</p>
 *
 * <p>{@link #closeBillingCycle(String)} opens the transaction the outbox write joins. The account
 * row and the event row commit together, and {@code outbox/OutboxRelay.java} publishes the stored
 * row on a later sweep.
 *
 * <p>Each committed close counts once against {@code carddemo.account.cycle.closed}.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Service
public class BillingCycleService {

    /** Reads the account row under a write lock and stores it again. */
    private final AccountRepository accountRepository;

    /** Stores one event in the same transaction as the account row. */
    private final OutboxWriter outboxWriter;

    /** Explicit boundary around the account row and its outbox event. */
    private final TransactionTemplate transactionTemplate;

    /** Records a close only after commit and a failure only after rollback. */
    private final AccountMeters meters;

    /**
     * Builds the service and resolves the counter one close increments.
     *
     * <p>Resolution by name returns the meter already registered under
     * {@code carddemo.account.cycle.closed}. The registry holds one counter for that name.</p>
     *
     * @param accountRepository finds and stores the account row; must not be {@code null}
     * @param outboxWriter      stores the event row; must not be {@code null}
     * @param transactionTemplate boundary around one close attempt; must not be {@code null}
     * @param meters            recording surface used after the transaction completes
     * @throws NullPointerException when an argument is {@code null}
     */
    public BillingCycleService(AccountRepository accountRepository, OutboxWriter outboxWriter,
            TransactionTemplate transactionTemplate, AccountMeters meters) {
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must be present");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must be present");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
    }

    /**
     * Zeroes both cycle accumulators of one account and stores one event for publication.
     *
     * <p>The method changes two fields and reads four more for the event payload. It leaves
     * {@code current_balance}, {@code cash_credit_limit}, {@code open_date}, {@code reissue_date},
     * {@code address_zip}, {@code group_id} and {@code active_status} as it found them.</p>
     *
     * <p>A missing identifier yields an empty {@link Optional} and stores no event. The outbox
     * write carries {@code Propagation.MANDATORY}, and the explicit transaction template supplies
     * the transaction it joins.</p>
     *
     * @param accountId the eleven-digit account identifier; must not be {@code null}
     * @return the stored account with both accumulators at zero, or an empty {@link Optional} when
     *         no row carries that identifier
     * @throws NullPointerException when {@code accountId} is {@code null}
     */
    public Optional<AccountEntity> closeBillingCycle(String accountId) {
        Objects.requireNonNull(accountId, "accountId must be present");

        try {
            Optional<AccountEntity> closed = Objects.requireNonNull(
                    transactionTemplate.execute(status -> closeWithinTransaction(accountId)),
                    "the cycle-close transaction must answer with a result");
            if (closed.isPresent()) {
                meters.recordCycleClosed();
            }
            return closed;
        } catch (RuntimeException failure) {
            meters.recordCycleCloseFailure();
            throw failure;
        }
    }

    /** Runs the locked read, update, and outbox write inside one transaction. */
    private Optional<AccountEntity> closeWithinTransaction(String accountId) {
        Optional<AccountEntity> stored = accountRepository.findForUpdateByAccountId(accountId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        AccountEntity account = stored.get();
        account.setCurrentCycleCredit(zeroAt(PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
        account.setCurrentCycleDebit(zeroAt(PicClause.ACCT_CURR_CYC_DEBIT_SCALE));

        AccountEntity closed = accountRepository.save(account);

        outboxWriter.write(AccountStateChanged.of(closed.getAccountId(),
                ChangeKind.BILLING_CYCLE_CLOSED, closed.getCurrentBalance(),
                closed.getCreditLimit(), closed.getCurrentCycleCredit(),
                closed.getCurrentCycleDebit(), closed.getExpirationDate()));

        return Optional.of(closed);
    }

    /**
     * Returns zero at the scale one Picture field holds.
     *
     * <p>{@link CobolDecimal#truncateToScale(BigDecimal, int)} fixes the fractional digits at
     * {@code scale}. Zero at scale 2 matches {@code NUMERIC(12,2)}, the column type both
     * accumulators hold.</p>
     *
     * @param scale fractional digits the target field holds, from {@link PicClause}
     * @return zero at {@code scale}
     */
    private static BigDecimal zeroAt(int scale) {
        return CobolDecimal.truncateToScale(BigDecimal.ZERO, scale);
    }
}
