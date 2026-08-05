package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Finds and stores the running balance one account holds for one transaction type and category.
 *
 * <p>{@link TransactionCategoryBalanceEntity.TransactionCategoryBalanceId} carries the
 * {@code TRAN-CAT-KEY} group at {@code app/cpy/CVTRA01Y.cpy:L5-L8}, whose three parts span the
 * seventeen bytes {@code app/jcl/TCATBALF.jcl:L40} declares as {@code KEYS(17 0)}.</p>
 *
 * <p>{@code findById} returns an empty {@code Optional} for a key the table does not hold and never
 * throws, and the caller then creates the row, matching {@code app/cbl/CBTRN02C.cbl:L481}.</p>
 *
 * <p>The source construct is the parameter area {@code LK-M03B-AREA} at
 * {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code dispatches every read and write
 * through one subroutine.</p>
 *
 * <p>The aggregate supports a keyed read and a save, and it supports no removal.
 * {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:L467-L501} reads, then branches to
 * {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L503} for a {@code WRITE} or to
 * {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L526} for a {@code REWRITE}. Both branches end in
 * {@code save} here. No paragraph deletes a category balance, and the accumulator it carries is
 * cleared by assignment and never by removing the row. Extending {@link Repository} keeps every
 * {@code delete} off the interface.</p>
 */
public interface TransactionCategoryBalanceRepository
        extends Repository<TransactionCategoryBalanceEntity,
                TransactionCategoryBalanceEntity.TransactionCategoryBalanceId> {

    /**
     * Finds the running balance for one account, transaction type and category.
     *
     * <p>An empty {@code Optional} is the {@code INVALID KEY} condition at
     * {@code app/cbl/CBTRN02C.cbl:L475-L479}, which raises the create flag. The caller then builds
     * a new row, matching {@code 2700-A-CREATE-TCATBAL-REC}.
     *
     * @param key the account identifier, transaction type code and transaction category code
     * @return the row, or an empty {@code Optional} when the table holds no such key
     */
    Optional<TransactionCategoryBalanceEntity> findById(
            TransactionCategoryBalanceEntity.TransactionCategoryBalanceId key);

    /**
     * Saves a category balance, whether the caller has just built it or has changed one it read.
     *
     * @param categoryBalance the row to save
     * @return the saved row
     */
    TransactionCategoryBalanceEntity save(TransactionCategoryBalanceEntity categoryBalance);

    /**
     * Adds one amount to the balance the three key parts name, creating the row at that amount when
     * the table holds none, in one statement.
     *
     * <p>This carries both branches of {@code 2700-UPDATE-TCATBAL}:
     * {@code 2700-A-CREATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl:L503-L524} is the insert,
     * whose {@code INITIALIZE} at {@code :L504} then {@code ADD} at {@code :L508} makes the opening
     * balance the amount itself, and {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L526-L542} is the
     * conflict arm, whose {@code ADD} at {@code :L527} raises the stored balance.
     *
     * <p>A read that reports no row, followed by an insert, has a window: a second caller reads
     * nothing as well and one of the two inserts fails on the primary key. The source runs as a
     * single batch job over one file and never meets that window; a consumer group can hold more
     * than one instance and does. {@code ON CONFLICT ... DO UPDATE} has no window, because the
     * primary key decides the race inside the statement.
     *
     * <p>Addition happens in the database at {@code NUMERIC(11,2)}, which is the scale
     * {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9} declares and the scale
     * {@code src/main/resources/db/migration/V1__schema.sql} gives the column, so the sum needs no
     * rounding and cannot acquire a third fractional digit. A negative amount lowers the balance,
     * matching a refund on the source path.
     *
     * <p>The statement is written in Structured Query Language (SQL) rather than the Jakarta
     * Persistence Query Language, which has no upsert. {@code currentSchema} in the datasource
     * Uniform Resource Locator of {@code src/main/resources/application.yml} puts the schema this
     * service owns on the connection search path, so the unqualified table name resolves to it.
     *
     * @param accountId    eleven digits, from {@code TRANCAT-ACCT-ID PIC 9(11)}
     * @param typeCode     two characters, from {@code TRANCAT-TYPE-CD PIC X(02)}
     * @param categoryCode four digits, from {@code TRANCAT-CD PIC 9(04)}
     * @param amount       the signed amount to add, at two fractional digits
     * @return 1, the one row the statement writes on either arm
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO transaction_category_balance
                   (account_id, type_code, category_code, category_balance)
            VALUES (:accountId, :typeCode, :categoryCode, :amount)
            ON CONFLICT (account_id, type_code, category_code) DO UPDATE
               SET category_balance =
                   transaction_category_balance.category_balance + EXCLUDED.category_balance
            """, nativeQuery = true)
    int addToCategoryBalance(@Param("accountId") String accountId,
            @Param("typeCode") String typeCode,
            @Param("categoryCode") String categoryCode,
            @Param("amount") BigDecimal amount);

    /**
     * Counts the category balance rows.
     *
     * @return how many rows the table holds
     */
    long count();
}
