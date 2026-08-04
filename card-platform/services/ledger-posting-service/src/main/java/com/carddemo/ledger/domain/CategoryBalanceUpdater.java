package com.carddemo.ledger.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.TransactionCategoryBalanceId;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Adds one transaction amount to the balance an account holds for one transaction type and
 * category, creating the row when the table holds none. Reproduces {@code 2700-UPDATE-TCATBAL}
 * and its two branches at {@code app/cbl/CBTRN02C.cbl:L467-L542}.
 *
 * <p>An absent row is a create signal and not a failure, matching the acceptance of both file
 * statuses at {@code :L481}. Each write branch accepts one status only, at {@code :L512} and at
 * {@code :L530}, so a store failure travels on to the caller.
 *
 * <p>The key is the three-part group {@code 05 TRAN-CAT-KEY.} at
 * {@code app/cpy/CVTRA01Y.cpy:L5-L8}, seventeen bytes wide per {@code app/jcl/TCATBALF.jcl:L40}.
 * Each part stays a string, so zero padding survives. Both additions truncate toward zero through
 * {@link CobolDecimal}, a negative amount lowers the balance, and the caller owns the transaction
 * boundary. Source-to-target mapping is recorded in
 * {@code card-platform/docs/traceability-matrix.md}.
 */
@Service
public class CategoryBalanceUpdater {

    private static final Logger log = LoggerFactory.getLogger(CategoryBalanceUpdater.class);

    /** Reads one category balance by key and stores it. */
    private final TransactionCategoryBalanceRepository categoryBalances;

    /**
     * Takes the store of category balances.
     *
     * @param categoryBalances store the keyed read and the save run against
     */
    public CategoryBalanceUpdater(TransactionCategoryBalanceRepository categoryBalances) {
        this.categoryBalances = categoryBalances;
    }

    /**
     * Adds {@code amount} to the balance the three key parts name, creating the row when the table
     * holds none.
     *
     * <p>The keyed read at {@code app/cbl/CBTRN02C.cbl:L474-L479} raises a create flag on
     * {@code INVALID KEY}, and the fork at {@code :L495-L499} picks the branch.
     *
     * @param accountId            eleven digits, from {@code XREF-ACCT-ID PIC 9(11)} at
     *                             {@code app/cpy/CVACT03Y.cpy:L7}
     * @param transactionTypeCode  two characters, from {@code DALYTRAN-TYPE-CD PIC X(02)} at
     *                             {@code app/cpy/CVTRA06Y.cpy:L6}
     * @param merchantCategoryCode four digits, from {@code DALYTRAN-CAT-CD PIC 9(04)} at
     *                             {@code app/cpy/CVTRA06Y.cpy:L7}
     * @param amount               the signed amount, from {@code DALYTRAN-AMT PIC S9(09)V99} at
     *                             {@code app/cpy/CVTRA06Y.cpy:L10}
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if a key part, or the balance the addition yields, is not
     *                                  the shape its column holds
     */
    public void updateCategoryBalance(String accountId,
                                      String transactionTypeCode,
                                      String merchantCategoryCode,
                                      BigDecimal amount) {
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(accountId,
                transactionTypeCode, merchantCategoryCode);
        Optional<TransactionCategoryBalanceEntity> existingRow = categoryBalances.findById(key);
        if (existingRow.isEmpty()) {
            createRow(key, amount);
        } else {
            addToExistingRow(existingRow.get(), amount);
        }
    }

    /**
     * Builds the row and stores it, reproducing {@code 2700-A-CREATE-TCATBAL-REC} at
     * {@code app/cbl/CBTRN02C.cbl:L503-L524}.
     *
     * <p>{@code INITIALIZE} at {@code :L504} zeroes the whole record including the key, so
     * {@code :L505-L507} set it again before {@code :L508} adds the amount to zero.
     *
     * @param key    the three parts the new row carries
     * @param amount the signed amount added to zero
     */
    private void createRow(TransactionCategoryBalanceId key, BigDecimal amount) {
        log.debug("No category balance row for account {}, type {}, category {}; creating it",
                key.getAccountId(), key.getTypeCode(), key.getCategoryCode());
        BigDecimal openingBalance =
                CobolDecimal.add(BigDecimal.ZERO, amount, PicClause.TRAN_CAT_BAL_SCALE);
        categoryBalances.save(new TransactionCategoryBalanceEntity(key, openingBalance));
    }

    /**
     * Adds the amount to the balance the row carries and stores the result, reproducing
     * {@code 2700-B-UPDATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl:L526-L542}.
     *
     * <p>The row exposes getters alone, so the sum travels in a new instance carrying the same key.
     * Saving under a key the table already holds is the {@code REWRITE} at {@code :L528}.
     *
     * @param existingRow the row the keyed read returned
     * @param amount      the signed amount added to its balance
     */
    private void addToExistingRow(TransactionCategoryBalanceEntity existingRow,
            BigDecimal amount) {
        BigDecimal updatedBalance = CobolDecimal.add(existingRow.getCategoryBalance(), amount,
                PicClause.TRAN_CAT_BAL_SCALE);
        categoryBalances.save(new TransactionCategoryBalanceEntity(existingRow.getId(),
                updatedBalance));
    }
}
