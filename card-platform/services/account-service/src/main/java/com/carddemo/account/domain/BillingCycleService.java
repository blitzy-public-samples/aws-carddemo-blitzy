package com.carddemo.account.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.AccountStateChanged.ChangeKind;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;

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
 * row on a later sweep. Event paths: {@code card-platform/docs/event-flow.md}.</p>
 *
 * <p>Each committed close counts once against {@code carddemo.account.cycle.closed}.</p>
 */
@Service
public class BillingCycleService {

    /**
     * Name of the counter one committed close increments. The account service registers this meter
     * at start-up and exposes it under {@code /actuator/metrics}.
     */
    private static final String CYCLE_CLOSED_METER = "carddemo.account.cycle.closed";

    /** Description the registered counter carries. */
    private static final String CYCLE_CLOSED_DESCRIPTION = "Billing cycle closes that committed";

    /** Reads the account row under a write lock and stores it again. */
    private final AccountRepository accountRepository;

    /** Stores one event in the same transaction as the account row. */
    private final OutboxWriter outboxWriter;

    /** Counts closes that reached a commit. */
    private final Counter cycleClosed;

    /**
     * Builds the service and resolves the counter one close increments.
     *
     * <p>Resolution by name returns the meter already registered under
     * {@code carddemo.account.cycle.closed}. The registry holds one counter for that name.</p>
     *
     * @param accountRepository finds and stores the account row; must not be {@code null}
     * @param outboxWriter      stores the event row; must not be {@code null}
     * @param meterRegistry     the registry the counter resolves against; must not be {@code null}
     * @throws NullPointerException when an argument is {@code null}
     */
    public BillingCycleService(AccountRepository accountRepository, OutboxWriter outboxWriter,
            MeterRegistry meterRegistry) {
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must be present");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must be present");
        Objects.requireNonNull(meterRegistry, "meterRegistry must be present");
        this.cycleClosed = Counter.builder(CYCLE_CLOSED_METER)
                .description(CYCLE_CLOSED_DESCRIPTION)
                .register(meterRegistry);
    }

    /**
     * Zeroes both cycle accumulators of one account and stores one event for publication.
     *
     * <p>The method changes two fields and reads three more for the event payload. It leaves
     * {@code current_balance}, {@code cash_credit_limit}, {@code open_date}, {@code reissue_date},
     * {@code address_zip}, {@code group_id} and {@code active_status} as it found them.</p>
     *
     * <p>A missing identifier yields an empty {@link Optional} and stores no event. The outbox
     * write carries {@code Propagation.MANDATORY}, and the annotation on this method supplies the
     * transaction it joins.</p>
     *
     * @param accountId the eleven-digit account identifier; must not be {@code null}
     * @return the stored account with both accumulators at zero, or an empty {@link Optional} when
     *         no row carries that identifier
     * @throws NullPointerException when {@code accountId} is {@code null}
     */
    @Transactional
    public Optional<AccountEntity> closeBillingCycle(String accountId) {
        Objects.requireNonNull(accountId, "accountId must be present");

        Optional<AccountEntity> stored = accountRepository.findForUpdateByAccountId(accountId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        AccountEntity account = stored.get();
        account.setCurrentCycleCredit(zeroAt(PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
        account.setCurrentCycleDebit(zeroAt(PicClause.ACCT_CURR_CYC_DEBIT_SCALE));

        AccountEntity closed = accountRepository.save(account);

        outboxWriter.write(AccountStateChanged.of(closed.getAccountId(),
                ChangeKind.BILLING_CYCLE_CLOSED, closed.getCreditLimit(),
                closed.getCurrentCycleCredit(), closed.getCurrentCycleDebit(),
                closed.getExpirationDate()));

        cycleClosed.increment();
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
