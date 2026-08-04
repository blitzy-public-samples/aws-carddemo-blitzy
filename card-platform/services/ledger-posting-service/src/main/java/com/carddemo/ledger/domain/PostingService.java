package com.carddemo.ledger.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.entity.TransactionEntity;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts one authorized transaction. The service copies twelve fields onto the transaction row and
 * stamps the processing timestamp. The category-balance update, the account-balance update and the
 * transaction insert then run in the order {@code app/cbl/CBTRN02C.cbl:L440-L442} fixes.
 *
 * <p>Reproduces {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl:L424-L444}, with
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code :L562-L579} as its third step. The twelve copies at
 * {@code :L425-L436} carry {@code app/cpy/CVTRA06Y.cpy:L5-L16} onto
 * {@code app/cpy/CVTRA05Y.cpy:L5-L16}.
 *
 * <p>{@code TRAN-PROC-TS} at {@code app/cpy/CVTRA05Y.cpy:L17} is not one of the twelve. One call to
 * {@link CobolDecimal#formatProcessingTimestamp} per posting replaces {@code :L437-L438}, and that
 * one value reaches the transaction row and {@link TransactionPosted#postedAt()} alike.
 *
 * <p>Deviations for this class are recorded in {@code card-platform/docs/decision-log.md} and
 * {@code card-platform/docs/traceability-matrix.md}.
 */
@Service
public class PostingService {

    /** Adds the amount to the balance one account holds for one transaction type and category. */
    private final CategoryBalanceUpdater categoryBalanceUpdater;

    /** Adds the amount to the account balance and to one billing-cycle accumulator. */
    private final AccountBalanceUpdater accountBalanceUpdater;

    /** Inserts the posted-transaction row. */
    private final TransactionRepository transactionRepository;

    /** Enqueues the posted event as an unpublished outbox row. */
    private final OutboxWriter outboxWriter;

    /**
     * Takes the two updaters, the transaction store and the outbox writer.
     *
     * @param categoryBalanceUpdater the first update, {@code app/cbl/CBTRN02C.cbl:L440}
     * @param accountBalanceUpdater  the second update, {@code app/cbl/CBTRN02C.cbl:L441}
     * @param transactionRepository  the store the third update inserts through,
     *                               {@code app/cbl/CBTRN02C.cbl:L442}
     * @param outboxWriter           the writer one posted event is enqueued through
     * @throws NullPointerException if any argument is {@code null}
     */
    public PostingService(CategoryBalanceUpdater categoryBalanceUpdater,
            AccountBalanceUpdater accountBalanceUpdater,
            TransactionRepository transactionRepository,
            OutboxWriter outboxWriter) {
        this.categoryBalanceUpdater = Objects.requireNonNull(categoryBalanceUpdater,
                "categoryBalanceUpdater is required");
        this.accountBalanceUpdater = Objects.requireNonNull(accountBalanceUpdater,
                "accountBalanceUpdater is required");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository is required");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter is required");
    }

    /**
     * Posts one authorized transaction and enqueues one {@link TransactionPosted} event.
     *
     * <p>The three updates and the outbox row join the transaction the caller opened, so they
     * commit together or roll back together. A store failure reaches the caller unchanged.
     *
     * @param event the authorized transaction to post
     * @throws NullPointerException when {@code event} is {@code null}
     * @throws AccountBalanceUpdater.AccountBalanceRowMissingException when no balance row carries
     *                                  the account identifier the event names
     * @throws IllegalArgumentException when a value the event carries does not fit the column or
     *                                  the contract that holds it
     */
    @Transactional
    public void postTransaction(TransactionAuthorized event) {
        Objects.requireNonNull(event, "event is required");

        String accountId = event.accountId();
        BigDecimal amount = event.amount();
        String processedTimestamp = CobolDecimal.formatProcessingTimestamp(LocalDateTime.now());
        TransactionEntity postedRow = buildTransactionRow(event, processedTimestamp);

        categoryBalanceUpdater.updateCategoryBalance(accountId, event.transactionTypeCode(),
                event.merchantCategoryCode(), amount);
        BigDecimal newBalance = accountBalanceUpdater.updateBalances(accountId, amount);
        transactionRepository.save(postedRow);

        outboxWriter.write(TransactionPosted.forAccount(accountId, event.transactionId(),
                newBalance, processedTimestamp, amount, event.maskedCardNumber()));
    }

    /**
     * Builds the row from the twelve values {@code app/cbl/CBTRN02C.cbl:L425-L436} moves, plus the
     * stamp {@code :L438} stores.
     *
     * <p>Each value keeps the width its event component carries, and the card number arrives
     * masked. The trailing {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:L18} holds no
     * column.
     *
     * @param event              the authorized transaction the twelve values come from
     * @param processedTimestamp the rendered stamp for {@code TRAN-PROC-TS} at
     *                           {@code app/cpy/CVTRA05Y.cpy:L17}
     * @return the row the third update inserts
     * @throws IllegalArgumentException when a value does not fit the column that holds it
     */
    private static TransactionEntity buildTransactionRow(TransactionAuthorized event,
            String processedTimestamp) {
        return new TransactionEntity(
                event.transactionId(),
                event.transactionTypeCode(),
                event.merchantCategoryCode(),
                event.source(),
                event.description(),
                event.amount(),
                event.merchantId(),
                event.merchantName(),
                event.merchantCity(),
                event.merchantZip(),
                event.maskedCardNumber(),
                event.authorizedAt(),
                processedTimestamp);
    }
}
