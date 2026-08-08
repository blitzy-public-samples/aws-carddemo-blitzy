package com.carddemo.account.domain;

import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.AccountStateChanged.ChangeKind;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds one posted amount to the account record this service owns, then to one of its two
 * billing-cycle accumulators, and stores the state change for publication.
 *
 * <p>Reproduces {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560}.
 * {@code L547} adds the transaction amount to {@code ACCT-CURR-BAL}. {@code L548} then routes the
 * same amount by sign: zero or above to {@code ACCT-CURR-CYC-CREDIT} at {@code L549}, negative to
 * {@code ACCT-CURR-CYC-DEBIT} at {@code L551}. One accumulator moves per call and the other keeps
 * its stored value. The {@code REWRITE} at {@code L554} becomes a save of the same row. All three
 * fields are {@code PIC S9(10)V99}, at {@code app/cpy/CVACT01Y.cpy:L7}, {@code :L13} and
 * {@code :L14}.
 *
 * <h2>Why the account service performs this arithmetic at all</h2>
 *
 * <p>The source had one {@code ACCTDAT} record. The batch posting program added to it at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}, the account view program read the same record at
 * {@code app/cbl/COACTVWC.cbl}, and the interest program zeroed the two accumulators on the same
 * record at {@code app/cbl/CBACT04C.cbl:L353-L354}. One record, three readers, nothing to
 * reconcile.
 *
 * <p>This platform splits that record across three services, and the split severed the chain the
 * credit-limit rule depends on. {@code ledger-posting-service} derives the balance from the
 * postings it applies, and it holds a copy of three columns. This service owns the record itself,
 * and {@code authorization-service} holds a copy of the credit limit, the expiry and the two
 * accumulators that reject reason 102 tests at {@code app/cbl/CBTRN02C.cbl:L403-L413}. Before this
 * class existed nothing carried a posted amount into either the record here or that copy, so the
 * two accumulators stayed at their seeded values for ever: reason 102 degenerated into a test of
 * one amount against the credit limit, cumulative cycle exposure was never enforced, and the
 * account view reported the balance as it stood at deployment. Both are equivalence failures rather
 * than cosmetic ones, and this class is what closes them.
 *
 * <p>Nothing here reaches another service. The amount arrives on an event, the write is local, and
 * the state change leaves through the outbox this service already owns. The authorization service's
 * own listener then refreshes its copy, so the rule reads the accumulators this method moved.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It applies the amount and never the balance the event carries. The ledger derives its own copy
 * of the balance from the same amounts, so both arrive at the same value from the same starting
 * point, and each service performs the arithmetic on the record it owns rather than accepting a
 * value another service computed. This is the source arithmetic reproduced twice on two copies, not
 * one copy overwriting the other.
 *
 * <p>It writes no other column. The credit limit, the cash credit limit, the three dates, the
 * postal code, the group identifier and the active status stay exactly as the update path left them.
 * A posting in the source moved three fields, and so does this.
 *
 * <p>It performs no validation of its own. The event has already passed
 * {@code schemas/transaction-posted-v2.json} on both the publish and the consume side, and a
 * negative amount is ordinary traffic: {@code app/cbl/CBTRN02C.cbl:L550-L551} routes one to the
 * debit accumulator deliberately.
 *
 * <p>Every arithmetic step runs through {@link CobolDecimal}, which pins truncation toward zero. The
 * {@code ROUNDED} phrase appears in none of the twenty-eight programs under {@code app/cbl/}, so a
 * store there truncates, and half-up rounding would differ by a cent without failing anything.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Event paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@Service
public class PostedTransactionService {

    /** Writes the two diagnostic lines this class emits, neither carrying a monetary value. */
    private static final Logger LOG = LoggerFactory.getLogger(PostedTransactionService.class);

    /** Reads the account row under a write lock and stores it again. */
    private final AccountRepository accountRepository;

    /** Stores the state change in the same transaction as the account row. */
    private final OutboxWriter outboxWriter;

    /**
     * Takes the store and the outbox writer.
     *
     * @param accountRepository finds and stores the account row; must not be {@code null}
     * @param outboxWriter      stores the event row; must not be {@code null}
     * @throws NullPointerException when an argument is {@code null}
     */
    public PostedTransactionService(AccountRepository accountRepository,
            OutboxWriter outboxWriter) {
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must be present");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must be present");
    }

    /**
     * Applies one posted amount to one account, and stores the resulting state change.
     *
     * <p>The read takes a write lock on the row and the caller's commit releases it, so a second
     * posting for the same account waits and then reads the values this call stored. Without the
     * lock two postings read one balance and the second store loses the first amount.
     *
     * <p>{@link Propagation#MANDATORY} joins the transaction the caller opened and starts none. The
     * caller is {@code messaging/TransactionPostedConsumer}, whose transaction also carries the
     * processed-event marker, so the marker, the account row and the outbox row commit together or
     * roll back together. A rolled-back marker is what lets the redelivery that follows apply the
     * amount exactly once.
     *
     * @param accountId     the eleven-digit account identifier the event names
     * @param amount        the posted amount, from {@code TRAN-AMT PIC S9(09)V99} at
     *                      {@code app/cpy/CVTRA05Y.cpy:L10}. A negative value is a refund and is
     *                      ordinary traffic
     * @param transactionId the transaction the amount belongs to, named in a diagnostic so a
     *                      reader can follow one posting without a monetary value being logged
     * @return the account row as stored, carrying the balance and both accumulators after the add
     * @throws NullPointerException      when an argument is {@code null}
     * @throws AccountRowMissingException when this service holds no account under that identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountEntity applyPostedAmount(String accountId, BigDecimal amount,
            String transactionId) {
        Objects.requireNonNull(accountId, "accountId must be present");
        Objects.requireNonNull(amount, "amount must be present");
        Objects.requireNonNull(transactionId, "transactionId must be present");

        AccountEntity account = accountRepository.findForUpdateByAccountId(accountId)
                .orElseThrow(AccountRowMissingException::new);

        account.setCurrentBalance(CobolDecimal.add(account.getCurrentBalance(), amount,
                PicClause.ACCT_CURR_BAL_SCALE));
        if (amount.signum() >= 0) {
            account.setCurrentCycleCredit(CobolDecimal.add(account.getCurrentCycleCredit(), amount,
                    PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
        } else {
            account.setCurrentCycleDebit(CobolDecimal.add(account.getCurrentCycleDebit(), amount,
                    PicClause.ACCT_CURR_CYC_DEBIT_SCALE));
        }

        AccountEntity posted = accountRepository.save(account);

        outboxWriter.write(AccountStateChanged.of(posted.getAccountId(), ChangeKind.ACCOUNT_UPDATED,
                posted.getCurrentBalance(), posted.getCreditLimit(), posted.getCurrentCycleCredit(),
                posted.getCurrentCycleDebit(), posted.getExpirationDate()));

        LOG.info("Transaction {} moved the balance and one billing-cycle accumulator of the account"
                + " record, and one state change is queued for publication.", transactionId);
        return posted;
    }

    /**
     * Raised when a posted transaction names an account this service holds no row for.
     *
     * <p>The source could not reach this state: the posting program and the account view read one
     * {@code ACCTDAT} record, so a posting always found the record it was posting to. Here the
     * amount arrives on an event, and an account absent from this service is a real inconsistency
     * rather than a message to discard.
     *
     * <p>Failing is deliberate. The delivery stays unacknowledged, the container retries it under
     * {@code carddemo.consumer.retry}, and a still-absent row sends the record to the dead-letter
     * topic where an operator can see it. Silently ignoring the amount would leave the account view
     * and the ledger permanently apart with nothing recorded.
     */
    public static final class AccountRowMissingException extends RuntimeException {

        /** Serialization identity of this exception. */
        private static final long serialVersionUID = 1L;

        /**
         * The whole text this exception carries. It names no account, no amount and no card.
         *
         * <p>An account identifier is pseudonymous rather than absent: the customer record of this
         * service resolves one to a named cardholder, so an identifier in an exception message is an
         * identifier in every place an exception message reaches. A message is written to a log by
         * whatever logs the failure, attached to a span by an instrumentation agent, and captured by
         * an error-reporting integration, and none of those three is a surface this service redacts.
         */
        public static final String MESSAGE =
                "no account row carries the identifier this posting named, so the posted amount"
                        + " reached no account record";

        /**
         * Carries the constant text and no value read from the event.
         *
         * <p>The identifier is not lost by being absent here. It is the message key of the delivery
         * and the {@code aggregateId} of the envelope, so {@code config/KafkaConsumerConfig} carries
         * it into the dead-letter record every exhausted delivery reaches, where an operator reads it
         * beside the source topic, partition and offset. That record is a surface this service
         * governs; an exception message is not.
         */
        public AccountRowMissingException() {
            super(MESSAGE);
        }
    }
}
