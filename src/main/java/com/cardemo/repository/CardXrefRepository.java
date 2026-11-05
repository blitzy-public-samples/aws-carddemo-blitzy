package com.cardemo.repository;

import com.cardemo.entity.CardXref;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link CardXref} entity.
 *
 * <p>Provides data access for the card cross-reference junction table,
 * migrated from the VSAM CARDXREF KSDS dataset (50-byte records defined
 * in copybook CVACT03Y.cpy with a 16-byte XREF-CARD-NUM primary key).</p>
 *
 * <h3>COBOL-to-Java Access Pattern Mapping</h3>
 *
 * <table>
 *   <caption>VSAM Access Pattern Translations</caption>
 *   <tr><th>COBOL Operation</th><th>Java Method</th><th>Source</th></tr>
 *   <tr>
 *     <td>{@code READ XREF-FILE KEY IS XREF-CARD-NUM}</td>
 *     <td>{@link #findByXrefCardNum(String)} / {@link #findById(Object)}</td>
 *     <td>CBTRN02C.cbl paragraph 1500-A-LOOKUP-XREF</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EXEC CICS READ DATASET('CXACAIX') RIDFLD(WS-XREF-ACCT-ID)}</td>
 *     <td>{@link #findByAccountId(String)}</td>
 *     <td>COACTVWC.cbl — AIX read by account ID</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EXEC CICS STARTBR / READNEXT DATASET('CARDXREF')}</td>
 *     <td>{@link #findAll()}</td>
 *     <td>COCRDLIC.cbl — card list browsing</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EXEC CICS WRITE DATASET('CARDXREF')}</td>
 *     <td>{@link #save(Object)}</td>
 *     <td>VSAM WRITE equivalent</td>
 *   </tr>
 * </table>
 *
 * <h3>VSAM Alternate Index (AIX) Mapping</h3>
 * <p>The original VSAM CARDXREF dataset has an alternate index (AIX) named
 * {@code CXACAIX} on the XREF-ACCT-ID field (position 25, length 11). This
 * AIX is replicated via a database index {@code idx_cardxref_acct_id} on the
 * {@code xref_acct_id} column and is queried through {@link #findByAccountId(String)}.
 * One account can have multiple cards, so the method returns a {@link List}.</p>
 *
 * @see CardXref
 * @see <a href="app/cpy/CVACT03Y.cpy">CVACT03Y.cpy — CARD-XREF-RECORD</a>
 * @see <a href="app/cbl/CBTRN02C.cbl">CBTRN02C.cbl — Daily transaction posting</a>
 * @see <a href="app/cbl/COACTVWC.cbl">COACTVWC.cbl — Account view</a>
 * @see <a href="app/cbl/COCRDLIC.cbl">COCRDLIC.cbl — Credit card list</a>
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Finds all cross-reference records for a specific account.
     *
     * <p>This method is the <strong>AIX equivalent</strong>, replicating the VSAM
     * alternate index (AIX) on XREF-ACCT-ID at position 25, length 11. The
     * original COBOL access pattern in COACTVWC.cbl is:</p>
     * <pre>
     * EXEC CICS READ DATASET('CXACAIX')
     *           INTO(CARD-XREF-RECORD)
     *           RIDFLD(WS-XREF-ACCT-ID)
     * END-EXEC
     * </pre>
     *
     * <p>Returns a {@link List} because one account can have multiple associated
     * cards (one-to-many relationship from account to card cross-references).</p>
     *
     * <p>Spring Data auto-implements this method by deriving a query from the
     * method name, matching against the {@link CardXref#getAccountId()} field
     * which is indexed via {@code idx_cardxref_acct_id}.</p>
     *
     * @param accountId the 11-digit account ID (XREF-ACCT-ID PIC 9(11))
     * @return list of cross-reference records for the given account; empty list
     *         if no cards are associated with the account
     */
    List<CardXref> findByAccountId(String accountId);

    /**
     * Finds the cross-reference record for a specific card number.
     *
     * <p>This is the <strong>primary key read equivalent</strong>, replicating
     * the VSAM keyed READ in CBTRN02C.cbl paragraph 1500-A-LOOKUP-XREF:</p>
     * <pre>
     * MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
     * READ XREF-FILE INTO CARD-XREF-RECORD
     *    INVALID KEY
     *      MOVE 100 TO WS-VALIDATION-FAIL-REASON
     *      MOVE 'INVALID CARD NUMBER FOUND'
     *        TO WS-VALIDATION-FAIL-REASON-DESC
     * END-READ
     * </pre>
     *
     * <p>While functionally equivalent to {@link #findById(Object)}, this method
     * is provided with an explicit name for clarity in service-layer code where
     * the card number lookup context is semantically important (e.g., batch
     * validation producing reject code 100 when the XREF is not found).</p>
     *
     * <p>Spring Data auto-implements this method by deriving a query from the
     * method name, matching against the {@link CardXref#getXrefCardNum()} field
     * (the entity primary key).</p>
     *
     * @param cardNum the 16-character card number (XREF-CARD-NUM PIC X(16))
     * @return an {@link Optional} containing the cross-reference record if found,
     *         or {@link Optional#empty()} if no XREF exists for the card number
     *         (equivalent to VSAM INVALID KEY / reject code 100)
     */
    Optional<CardXref> findByXrefCardNum(String cardNum);

    /**
     * Finds all cross-reference records for a specific customer.
     *
     * <p>Provides customer-to-card resolution by querying the XREF-CUST-ID
     * field. This supports lookups where all cards belonging to a customer
     * need to be enumerated (e.g., statement generation in CBSTM03A.CBL
     * where card numbers are resolved to customer/account for addressing).</p>
     *
     * <p>Returns a {@link List} because one customer can have multiple cards
     * across potentially different accounts.</p>
     *
     * <p>Spring Data auto-implements this method by deriving a query from the
     * method name, matching against the {@link CardXref#getCustId()} field.</p>
     *
     * @param custId the 9-digit customer ID (XREF-CUST-ID PIC 9(09))
     * @return list of cross-reference records for the given customer; empty list
     *         if no cards are associated with the customer
     */
    List<CardXref> findByCustId(String custId);
}
