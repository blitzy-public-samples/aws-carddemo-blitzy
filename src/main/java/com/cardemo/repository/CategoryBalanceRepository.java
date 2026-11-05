package com.cardemo.repository;

import com.cardemo.entity.CategoryBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link CategoryBalance} entity.
 *
 * <p>Maps data access patterns for the VSAM TCATBALF KSDS dataset (50-byte records
 * defined by the COBOL copybook {@code CVTRA07Y.cpy}). Category balances track
 * aggregated transaction amounts per account, transaction type, and category code.
 *
 * <h3>COBOL Source — VSAM Access Patterns (CBTRN02C.cbl paragraphs 2700–2750):</h3>
 * <ul>
 *   <li><strong>Keyed READ:</strong> {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
 *       KEY IS TRAN-CAT-KEY} — composite key lookup by (TRANCAT-ACCT-ID + TRANCAT-TYPE-CD
 *       + TRANCAT-CD). Mapped to
 *       {@link #findByAccountIdAndTypeCodeAndCategoryCode(String, String, Integer)}.</li>
 *   <li><strong>REWRITE:</strong> Update existing balance after adding transaction amount
 *       ({@code WS-TEMP-BAL = TRAN-CAT-BAL + DALYTRAN-AMT}). Mapped to inherited
 *       {@link #save(Object)}.</li>
 *   <li><strong>WRITE:</strong> Create new balance record when composite key is not found
 *       (WS-CREATE-TRANCAT-REC flag path). Mapped to inherited {@link #save(Object)}.</li>
 *   <li><strong>STARTBR/READNEXT (partial key):</strong> Browse all category balances
 *       for a specific account using partial key on TRANCAT-ACCT-ID. Mapped to
 *       {@link #findByAccountId(String)}.</li>
 * </ul>
 *
 * <h3>COBOL Composite Key (TRAN-CAT-KEY from CVTRA07Y.cpy):</h3>
 * <pre>
 * 05  TRAN-CAT-KEY.
 *    10 TRANCAT-ACCT-ID   PIC 9(11).   → accountId  (String, 11 chars)
 *    10 TRANCAT-TYPE-CD   PIC X(02).   → typeCode   (String, 2 chars)
 *    10 TRANCAT-CD        PIC 9(04).   → categoryCode (Integer)
 * </pre>
 *
 * <p>The entity uses {@code @IdClass(CategoryBalance.CategoryBalanceId.class)} to model
 * the composite primary key. The repository generic ID type is
 * {@link CategoryBalance.CategoryBalanceId} accordingly.
 *
 * @see CategoryBalance
 * @see CategoryBalance.CategoryBalanceId
 * @see <a href="app/cpy/CVTRA07Y.cpy">COBOL TRAN-CAT-BAL-RECORD copybook</a>
 * @see <a href="app/cbl/CBTRN02C.cbl">COBOL daily transaction posting program</a>
 */
@Repository
public interface CategoryBalanceRepository
        extends JpaRepository<CategoryBalance, CategoryBalance.CategoryBalanceId> {

    /**
     * Finds a category balance by the full composite key (account ID + type code + category code).
     *
     * <p>This is the primary access pattern used during daily transaction posting in
     * CBTRN02C.cbl paragraph 2700. It maps the COBOL VSAM keyed READ:
     * <pre>
     * READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *     KEY IS TRAN-CAT-KEY
     * </pre>
     *
     * <p>When the result is {@link Optional#empty()}, the caller should create a new
     * balance record (equivalent to the COBOL WS-CREATE-TRANCAT-REC flag path that
     * triggers a WRITE instead of REWRITE).
     *
     * @param accountId    the 11-character zero-padded account identifier (TRANCAT-ACCT-ID)
     * @param typeCode     the 2-character transaction type code (TRANCAT-TYPE-CD)
     * @param categoryCode the transaction category code as Integer (TRANCAT-CD)
     * @return an {@link Optional} containing the matching category balance, or empty
     *         if no record exists for the given composite key
     */
    Optional<CategoryBalance> findByAccountIdAndTypeCodeAndCategoryCode(
            String accountId, String typeCode, Integer categoryCode);

    /**
     * Finds all category balances for a specific account.
     *
     * <p>Maps a VSAM STARTBR/READNEXT pattern with a partial key on TRANCAT-ACCT-ID.
     * Used for reporting and account-level balance aggregation — retrieves every
     * (type, category) balance combination associated with the given account.
     *
     * @param accountId the 11-character zero-padded account identifier (TRANCAT-ACCT-ID)
     * @return a list of all category balance records for the account; empty list if none exist
     */
    List<CategoryBalance> findByAccountId(String accountId);
}
