/*
 * CardRepository.java — Spring Data JPA Repository for CARDDATA VSAM Dataset
 *
 * Source COBOL programs:
 *   - COCRDLIC.cbl (Credit Card List — STARTBR/READNEXT paginated browse)
 *   - COCRDSLC.cbl (Credit Card Detail — primary key READ, CARDAIX alternate index READ)
 *   - COCRDUPC.cbl (Credit Card Update — READ UPDATE / REWRITE with optimistic locking)
 *
 * Source copybook: app/cpy/CVACT02Y.cpy (CARD-RECORD, 150-byte KSDS)
 *
 * VSAM Dataset: AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
 * Primary Key: CARD-NUM PIC X(16) — 16-character card number
 * Alternate Index (AIX): CARD-ACCT-ID at position 16, length 11
 *   — Enables card-to-account lookup accessed via DATASET('CARDAIX') in COCRDSLC.cbl
 *
 * Transformation Mapping:
 *   EXEC CICS READ DATASET('CARDDAT') RIDFLD(card-num)     → findById(String)
 *   EXEC CICS READ DATASET('CARDAIX') RIDFLD(acct-id)      → findByAccountId(String)
 *   EXEC CICS STARTBR / READNEXT / ENDBR DATASET('CARDDAT') → findAll(Pageable)
 *   EXEC CICS STARTBR with CARD-ACCT-ID filter              → findByAccountId(String, Pageable)
 *   EXEC CICS READ UPDATE / REWRITE DATASET('CARDDAT')      → save() with @Version
 *   EXEC CICS WRITE DATASET('CARDDAT')                      → save() (new entity)
 *   EXEC CICS DELETE DATASET('CARDDAT')                      → deleteById(String)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.repository;

import com.cardemo.entity.Card;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Card} entity persistence.
 *
 * <p>Provides data access operations for the CARDDATA VSAM KSDS dataset
 * (150-byte records defined in CVACT02Y.cpy). The repository maps all
 * COBOL CICS VSAM access patterns to Spring Data JPA operations:</p>
 *
 * <ul>
 *   <li><strong>Primary key READ</strong> (COCRDSLC.cbl):
 *       {@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}
 *       → inherited {@link #findById(Object) findById(String)}</li>
 *   <li><strong>AIX alternate index READ</strong> (COCRDSLC.cbl):
 *       {@code EXEC CICS READ DATASET('CARDAIX') RIDFLD(WS-CARD-RID-ACCTID)}
 *       → {@link #findByAccountId(String)}</li>
 *   <li><strong>Paginated browse</strong> (COCRDLIC.cbl):
 *       {@code EXEC CICS STARTBR/READNEXT/ENDBR DATASET(LIT-CARD-FILE)}
 *       → inherited {@link #findAll(Pageable)}</li>
 *   <li><strong>Account-filtered paginated browse</strong> (COCRDLIC.cbl 9500-FILTER-RECORDS):
 *       STARTBR/READNEXT with CARD-ACCT-ID filtering
 *       → {@link #findByAccountId(String, Pageable)}</li>
 *   <li><strong>Card update with optimistic locking</strong> (COCRDUPC.cbl):
 *       {@code EXEC CICS READ UPDATE / REWRITE DATASET('CARDDAT')}
 *       → inherited {@link #save(Object)} with {@code @Version} on Card entity</li>
 * </ul>
 *
 * <p>The generic type parameters are:</p>
 * <ul>
 *   <li>{@code Card} — the JPA entity class (CARDDATA 150-byte record)</li>
 *   <li>{@code String} — the primary key type (CARD-NUM PIC X(16), 16-character card number)</li>
 * </ul>
 *
 * @see Card
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Finds all cards associated with a specific account ID.
     *
     * <p>This method maps the VSAM Alternate Index (AIX) on CARD-ACCT-ID
     * (position 16, length 11 in the CARDDATA VSAM KSDS). In the original
     * COBOL program COCRDSLC.cbl, this access pattern is:</p>
     *
     * <pre>{@code
     * EXEC CICS READ
     *     DATASET('CARDAIX')
     *     INTO(CARD-RECORD)
     *     RIDFLD(WS-CARD-RID-ACCTID)
     *     RESP(WS-RESP-CD)
     *     RESP2(WS-REAS-CD)
     * END-EXEC
     * }</pre>
     *
     * <p>One account can have multiple cards, so the return type is
     * {@code List<Card>}. Spring Data auto-implements this method by
     * deriving a query from the method name, matching on the
     * {@link Card#getAccountId() accountId} property.</p>
     *
     * @param accountId the 11-character numeric account identifier
     *                  (e.g., "00000000050") to search for
     * @return a list of all cards associated with the specified account;
     *         an empty list if no cards are found for the account
     */
    List<Card> findByAccountId(String accountId);

    /**
     * Finds cards for a specific account with pagination support.
     *
     * <p>This method maps the COCRDLIC.cbl credit card list browse pattern
     * with account-based filtering. In the original COBOL program, the
     * 9500-FILTER-RECORDS paragraph checks:</p>
     *
     * <pre>{@code
     * IF CARD-ACCT-ID = WS-ACCTFILTER-CARDFILTER-I
     *     SET WS-DONOT-EXCLUDE-THIS-RECORD TO TRUE
     * END-IF
     * }</pre>
     *
     * <p>The COBOL browse uses {@code WS-MAX-SCREEN-LINES} (7 rows per page)
     * with PF7 (page up) and PF8 (page down) navigation. This paginated
     * query method provides equivalent functionality using Spring Data's
     * {@link Pageable} abstraction for page size and navigation control.</p>
     *
     * @param accountId the 11-character numeric account identifier
     *                  (e.g., "00000000050") to filter cards by
     * @param pageable  pagination and sorting parameters (page number,
     *                  page size, sort direction)
     * @return a {@link Page} of cards for the specified account containing
     *         the requested page of results with total count metadata
     */
    Page<Card> findByAccountId(String accountId, Pageable pageable);
}
