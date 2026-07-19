package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Transaction} entity. It replaces the legacy
 * {@code EXEC CICS} / {@code FILE SECTION} VSAM I/O against the {@code TRANSACT} KSDS with relational
 * access over PostgreSQL, as part of the AWS CardDemo COBOL-to-Java migration (AAP 0.3.3, 0.4.1, 0.6.2).
 *
 * <p><strong>Origin and traceability (AAP 0.6.10):</strong>
 * Origin: legacy/cpy/CVTRA05Y.cpy (TRAN-RECORD, RECLN 350); VSAM TRANSACT; transaction-by-card
 * alt-index per legacy/jcl/TRANIDX.jcl (KEYS(26 304)) + legacy/csd/CARDDEMO.CSD.</p>
 *
 * <p><strong>Key semantics (AAP 0.6.2):</strong> the VSAM primary key {@code TRAN-ID PIC X(16)} is
 * preserved as the entity identifier {@code tranId} (column {@code tran_id}), so this repository is
 * typed {@code JpaRepository<Transaction, String>}. Primary-key CRUD is served by the inherited
 * {@code findById(String)} and {@code save(...)} operations, exercised by transaction posting
 * ({@code CBTRN02C}), transaction view ({@code COTRN01C}), and transaction add ({@code COTRN02C}).</p>
 *
 * <p><strong>Alternate index (AAP 0.4.1):</strong> the legacy transaction-by-card alternate index
 * (alternate key {@code TRAN-CARD-NUM}) is reproduced by the derived query
 * {@link #findByCardNum(String)}, backed by the secondary database index on {@code transaction(card_num)}
 * created by the Flyway {@code V3} migration. It is consumed by statement generation
 * ({@code CBSTM03A} / {@code CBSTM03B}), which reads all transactions for a single card.</p>
 *
 * <p><strong>Ordering fidelity (AAP 0.6.6):</strong> {@link #findByCardNumOrderByTranIdAsc(String)}
 * returns a card's transactions ascending by {@code TRAN-ID}, preserving the legacy
 * {@code SORT FIELDS=(TRAN-ID,A)} statement/report ordering. The unfiltered full-list browse used by
 * the transaction-list screen ({@code COTRN00C}; {@code STARTBR} / {@code READNEXT} / {@code READPREV})
 * is served by the inherited {@code findAll(Sort)} / {@code findAll(Pageable)} with the caller supplying
 * {@code Sort.by("tranId")}; no explicit method is declared for that here. Ordering parity relies on the
 * {@code C} / {@code POSIX} collation applied to the {@code tran_id CHAR(16)} column by Flyway.</p>
 *
 * <p>No finders beyond primary-key CRUD and the by-card access path are declared. This preserves the
 * legacy VSAM access paths exactly and introduces no feature expansion (AAP 0.7.1).</p>
 *
 * @see Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns every transaction posted against the given card number, reproducing the legacy
     * transaction-by-card alternate index ({@code TRAN-CARD-NUM}, {@code legacy/jcl/TRANIDX.jcl}).
     * The derived query resolves against the {@link Transaction} property {@code cardNum}
     * (column {@code card_num}).
     *
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM PIC X(16)}) to match
     * @return the matching transactions in no guaranteed order; an empty list when the card has none
     */
    List<Transaction> findByCardNum(String cardNum);

    /**
     * Returns every transaction posted against the given card number, ordered ascending by transaction
     * id. This preserves the legacy {@code SORT FIELDS=(TRAN-ID,A)} ordering relied upon by statement
     * and report generation ({@code CBSTM03A} / {@code CBSTM03B}). The derived query resolves against
     * the {@link Transaction} properties {@code cardNum} (column {@code card_num}) and {@code tranId}
     * (column {@code tran_id}).
     *
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM PIC X(16)}) to match
     * @return the matching transactions ascending by {@code tranId}; an empty list when the card has none
     */
    List<Transaction> findByCardNumOrderByTranIdAsc(String cardNum);
}
