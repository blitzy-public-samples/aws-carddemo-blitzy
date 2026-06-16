package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Card} entity &mdash; the relational replacement for the
 * legacy VSAM {@code CARDDATA} key-sequenced dataset and its {@code CARDAIX} alternate index.
 *
 * <h2>Legacy lineage</h2>
 * <p>This repository abstracts all persistence access to the {@code cards} table, which is the Spring
 * Boot / Hibernate re-expression of the COBOL {@code CARD-RECORD} structure defined in copybook
 * {@code app/cpy/CVACT02Y.cpy} (record length 150). On the mainframe, that record lived in the VSAM
 * KSDS {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}, whose primary key is the 16-byte card number
 * ({@code KEYLEN 16}) and which carries a single alternate index over the 11-digit account id (the
 * {@code CARDAIX} path).</p>
 *
 * <p>The two legacy access paths are preserved here as:</p>
 * <ul>
 *   <li>the primary key {@code pk_cards (card_num)} &mdash; reached through the inherited
 *       {@link JpaRepository#findById(Object)} (with the {@link String} card number as the id) and
 *       {@link JpaRepository#save(Object)} operations; and</li>
 *   <li>the secondary index {@code idx_cards_acct_id (card_acct_id)} &mdash; reached through the
 *       derived query {@link #findByCardAcctId(Long, Pageable)} declared below.</li>
 * </ul>
 *
 * <h2>{@code COCRDLIC} &mdash; "List Credit Cards" screen replacement</h2>
 * <p>The online program {@code app/cbl/COCRDLIC.cbl} renders the paginated list of cards. Its
 * non-administrative path browses the {@code CARDAIX} alternate index for the single account carried
 * in the CICS {@code COMMAREA} ({@code CARD-ACCT-ID}) via the VSAM {@code STARTBR} / {@code READNEXT}
 * (and {@code READPREV}) verbs, returning a fixed window of <strong>7 rows per screen</strong>
 * ({@code WS-MAX-SCREEN-LINES VALUE 7}) and signalling whether a further page is available through the
 * {@code CA-NEXT-PAGE-EXISTS} indicator. That browse-with-paging behaviour is reproduced by the
 * {@link #findByCardAcctId(Long, Pageable)} method returning a {@link Page}: the page content mirrors
 * the screen rows, {@link Page#hasNext()} mirrors {@code CA-NEXT-PAGE-EXISTS}, and
 * {@link Page#getTotalElements()} supplies the total count VSAM never materialised directly.</p>
 *
 * <h2>Access-path summary</h2>
 * <table border="1">
 *   <caption>Legacy VSAM access path &rarr; repository operation</caption>
 *   <tr><th>Legacy operation</th><th>Repository operation</th></tr>
 *   <tr><td>{@code READ CARDDATA} by card number (RID)</td><td>inherited {@code findById(String)}</td></tr>
 *   <tr><td>{@code REWRITE CARDDATA} / add card</td><td>inherited {@code save(Card)}</td></tr>
 *   <tr><td>{@code STARTBR}/{@code READNEXT} on {@code CARDAIX} by {@code CARD-ACCT-ID} (page 7)</td>
 *       <td>{@link #findByCardAcctId(Long, Pageable)}</td></tr>
 *   <tr><td>Administrator "show all cards" full browse (page 7)</td>
 *       <td>inherited {@code findAll(Pageable)}</td></tr>
 * </table>
 *
 * <h2>Pagination convention &mdash; page size 7</h2>
 * <p>The legacy fixed page size of <strong>7</strong> rows is a business rule that callers must
 * honour (AAP &sect;0.7.1). This repository deliberately accepts an arbitrary {@link Pageable} and does
 * <em>not</em> hard-code the page size, keeping the data-access tier decoupled from the presentation
 * rule. The owning {@code CardService} supplies the page size by building
 * {@code PageRequest.of(page, com.carddemo.util.CardDemoConstants.PAGE_SIZE)} (where
 * {@code PAGE_SIZE == 7}) before delegating to {@link #findByCardAcctId(Long, Pageable)} or to the
 * inherited {@link JpaRepository#findAll(Pageable) findAll(Pageable)}.</p>
 *
 * <p>The repository carries no business logic: card-number / account-id immutability, CVV
 * suppression (the {@link Card} entity already {@code @JsonIgnore}s the CVV and the mapper layer never
 * copies it), and any validation are enforced by the entity, mapper, and service layers respectively.
 * Spring detects this interface as a repository bean automatically, so no {@code @Repository}
 * stereotype is required.</p>
 *
 * @see Card
 * @see JpaRepository
 * @see org.springframework.data.domain.PageRequest
 */
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Returns a single page of cards belonging to the given account, ordered and bounded by the
     * supplied {@link Pageable}.
     *
     * <p>This derived query resolves to {@code WHERE card_acct_id = :acctId} against the {@code cards}
     * table, targeting the {@link Card#getCardAcctId() cardAcctId} property (column
     * {@code card_acct_id}) and using the secondary index {@code idx_cards_acct_id} declared in
     * {@code V1__schema.sql}. It is the relational replacement for the VSAM {@code CARDAIX}
     * {@code STARTBR} / {@code READNEXT} browse performed by {@code app/cbl/COCRDLIC.cbl} for a single
     * account.</p>
     *
     * <p><strong>Caller convention:</strong> to preserve the legacy "List Credit Cards" screen window
     * of 7 rows, callers pass {@code PageRequest.of(pageNumber, 7)} (the {@code CardService} sources
     * the {@code 7} from {@code CardDemoConstants.PAGE_SIZE}). The returned {@link Page} exposes the
     * total element count and {@link Page#hasNext()}, reproducing the COBOL
     * {@code CA-NEXT-PAGE-EXISTS} paging indicator.</p>
     *
     * @param acctId   the owning account identifier to filter by (matches {@code Card.cardAcctId} /
     *                 column {@code card_acct_id}); must not be {@code null}
     * @param pageable the paging and sorting parameters (page index and size); callers supply page
     *                 size 7 to mirror the legacy screen &mdash; must not be {@code null}
     * @return a {@link Page} of {@link Card} rows for the account (possibly empty, never {@code null}),
     *         honouring the requested page bounds and carrying total-count and has-next metadata
     */
    Page<Card> findByCardAcctId(Long acctId, Pageable pageable);
}
