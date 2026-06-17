package com.carddemo.repository;

import com.carddemo.entity.CardXref;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link CardXref} entity &mdash; the canonical
 * <strong>card&nbsp;&rarr;&nbsp;customer&nbsp;&rarr;&nbsp;account</strong> cross-reference.
 *
 * <p>This interface abstracts all access to the {@code card_xref} relational table, which
 * replaces the legacy VSAM {@code CARDXREF} Key-Sequenced Data Set. The record layout it
 * serves is defined byte-for-byte by the COBOL copybook {@code app/cpy/CVACT03Y.cpy}
 * ({@code CARD-XREF-RECORD}, record length 50). In the mainframe application the
 * cross-reference was consulted in two distinct access patterns, both of which are reproduced
 * here as Spring Data derived queries:</p>
 *
 * <ol>
 *   <li><strong>Keyed read by card number.</strong> Batch transaction posting
 *       {@code app/cbl/CBTRN02C.cbl} (paragraph {@code 1500-A-LOOKUP-XREF}) issues
 *       {@code READ XREF-FILE} keyed by the daily-transaction card number to resolve the owning
 *       customer and account; an {@code INVALID KEY} condition raises validation reject code
 *       {@code 100} ({@code 'INVALID CARD NUMBER FOUND'}). This keyed {@code READ} is replaced
 *       by {@link #findByXrefCardNum(String)}.</li>
 *   <li><strong>Alternate-index browse by account id.</strong> Account-scoped card browsing
 *       walked the {@code CARDXREF.AIX} alternate index (over {@code XREF-ACCT-ID}). That browse
 *       is replaced by {@link #findByXrefAcctId(Long, Pageable)}, which is backed by the
 *       secondary index {@code idx_cardxref_acct_id} declared in
 *       {@code src/main/resources/db/migration/V1__schema.sql}.</li>
 * </ol>
 *
 * <p><strong>Identifier type.</strong> The repository's identifier type is {@link String}
 * because {@link CardXref} declares {@code @Id private String xrefCardNum}
 * (column {@code xref_card_num VARCHAR(16)}). Because {@code xrefCardNum} is the primary key,
 * {@link #findByXrefCardNum(String)} is functionally equivalent to
 * {@link JpaRepository#findById(Object)}; it is nevertheless provided as a named,
 * intention-revealing method that expresses &quot;look up the cross-reference by card
 * number&quot; exactly as {@code CBTRN02C} does.</p>
 *
 * <p><strong>Pagination convention.</strong> The legacy 3270 browse displayed a fixed page of
 * <strong>7</strong> rows per screen. That page size is supplied by the <em>caller</em> via
 * {@code PageRequest.of(pageIndex, 7)} and is deliberately <em>not</em> hard-coded in this
 * repository, keeping the data-access contract free of presentation concerns.</p>
 *
 * <p><strong>Layering.</strong> This is a pure data-access abstraction. No business logic and,
 * in particular, no reject-code (100 / 101) control flow lives here &mdash; that behavior is
 * owned by the batch and service layers (for example, {@code CBTRN02C}-derived transaction
 * posting). The interface is intentionally <em>not</em> annotated with {@code @Repository};
 * Spring Data automatically detects and proxies {@link JpaRepository} extensions.</p>
 *
 * @see CardXref
 * @see <a href="file:app/cpy/CVACT03Y.cpy">app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD)</a>
 * @see <a href="file:app/cbl/CBTRN02C.cbl">app/cbl/CBTRN02C.cbl (XREF keyed READ being replaced)</a>
 */
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Looks up a single cross-reference record by its card number (the primary key).
     *
     * <p>Replaces the keyed {@code READ XREF-FILE} of {@code CBTRN02C}'s
     * {@code 1500-A-LOOKUP-XREF} paragraph, which resolves the customer and account that own a
     * given card during daily transaction posting. A missing cross-reference corresponds to the
     * legacy {@code INVALID KEY} branch (validation reject code {@code 100},
     * {@code 'INVALID CARD NUMBER FOUND'}); callers express that outcome by handling the empty
     * {@link Optional}. The reject-code decision itself is made by the batch/service layer, not
     * here.</p>
     *
     * @param xrefCardNum the 16-character card number to resolve (the {@code @Id} of
     *                    {@link CardXref}; column {@code xref_card_num})
     * @return an {@link Optional} containing the matching {@link CardXref}, or
     *         {@link Optional#empty()} if no cross-reference exists for the supplied card number
     */
    Optional<CardXref> findByXrefCardNum(String xrefCardNum);

    /**
     * Returns a page of cross-reference records owned by the given account, ordered/paged as
     * specified by the supplied {@link Pageable}.
     *
     * <p>Replaces the {@code CARDXREF.AIX} alternate-index browse over {@code XREF-ACCT-ID} used
     * for account-scoped card listing. The query targets the entity property {@code xrefAcctId}
     * (column {@code xref_acct_id}) and is backed by the secondary index
     * {@code idx_cardxref_acct_id} created in {@code V1__schema.sql}.</p>
     *
     * <p>The fixed legacy screen page size of 7 is provided by the caller through the
     * {@link Pageable} argument (for example, {@code PageRequest.of(0, 7)}); this method does not
     * impose a page size of its own.</p>
     *
     * @param xrefAcctId the owning account identifier to filter by (column {@code xref_acct_id})
     * @param pageable   the pagination directive supplied by the caller (page index and size,
     *                   typically a size of 7 to mirror the legacy browse screen)
     * @return a {@link Page} of {@link CardXref} records for the account; never {@code null}, and
     *         empty when the account owns no cards
     */
    Page<CardXref> findByXrefAcctId(Long xrefAcctId, Pageable pageable);
}
