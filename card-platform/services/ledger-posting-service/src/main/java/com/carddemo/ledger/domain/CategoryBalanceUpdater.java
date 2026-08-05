package com.carddemo.ledger.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.TransactionCategoryBalanceId;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Adds one transaction amount to the balance an account holds for one transaction type and
 * category, creating the row when the table holds none. Reproduces {@code 2700-UPDATE-TCATBAL}
 * and its two branches at {@code app/cbl/CBTRN02C.cbl:L467-L542}.
 *
 * <p>An absent row is a create signal and not a failure, matching the acceptance of both file
 * statuses at {@code :L481}. Both branches reach the database as one upsert:
 * {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L503-L524} is the insert arm and
 * {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L526-L542} is the conflict arm. A store failure
 * travels on to the caller, which owns the transaction boundary.
 *
 * <p>The key is the three-part group {@code 05 TRAN-CAT-KEY.} at
 * {@code app/cpy/CVTRA01Y.cpy:L5-L8}, seventeen bytes wide per {@code app/jcl/TCATBALF.jcl:L40}.
 * Each part stays a string, so zero padding survives. The amount is truncated toward zero through
 * {@link CobolDecimal} before it reaches the statement, and a negative amount lowers the balance.
 * Source-to-target mapping is recorded in
 * {@code card-platform/docs/traceability-matrix.md}.
 */
@Service
public class CategoryBalanceUpdater {

    /** Rows one upsert writes, on either arm. */
    private static final int ROWS_ONE_UPSERT_WRITES = 1;

    /** Adds the amount to one keyed balance, inserting the row when the table holds none. */
    private final TransactionCategoryBalanceRepository categoryBalances;

    /**
     * Takes the store of category balances.
     *
     * @param categoryBalances store the upsert runs against
     * @throws NullPointerException if {@code categoryBalances} is {@code null}
     */
    public CategoryBalanceUpdater(TransactionCategoryBalanceRepository categoryBalances) {
        this.categoryBalances =
                Objects.requireNonNull(categoryBalances, "categoryBalances is required");
    }

    /**
     * Adds {@code amount} to the balance the three key parts name, creating the row when the table
     * holds none.
     *
     * <p>The keyed read at {@code app/cbl/CBTRN02C.cbl:L474-L479} raises a create flag on
     * {@code INVALID KEY}, and the fork at {@code :L495-L499} picks the branch. One statement
     * carries both arms here, so no window sits between the read and the write for a second
     * delivery of the same account to fall into.
     *
     * <p>Constructing the key first keeps the width and shape checks of
     * {@link TransactionCategoryBalanceId} on this path: a malformed key part fails before any
     * statement reaches the database.
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
     * @throws IllegalArgumentException if a key part is not the shape its column holds
     * @throws IllegalStateException    if the upsert reports any row count other than one
     */
    public void updateCategoryBalance(String accountId,
                                      String transactionTypeCode,
                                      String merchantCategoryCode,
                                      BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(accountId,
                transactionTypeCode, merchantCategoryCode);
        BigDecimal addend =
                CobolDecimal.add(BigDecimal.ZERO, amount, PicClause.TRAN_CAT_BAL_SCALE);

        int written = categoryBalances.addToCategoryBalance(key.getAccountId(), key.getTypeCode(),
                key.getCategoryCode(), addend);

        if (written != ROWS_ONE_UPSERT_WRITES) {
            throw new IllegalStateException("one category balance upsert writes "
                    + ROWS_ONE_UPSERT_WRITES + " row and this one reported " + written);
        }
    }
}
