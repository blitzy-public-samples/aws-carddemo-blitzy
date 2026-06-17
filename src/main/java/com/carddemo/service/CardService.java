package com.carddemo.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.PageResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.mapper.CardMapper;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.CardDemoConstants;

/**
 * Application service encapsulating the credit-card business operations of the AWS CardDemo
 * application: the paginated card list, the single-card detail view, and the card update.
 *
 * <h2>Legacy lineage &mdash; the three CICS online programs this service replaces</h2>
 * <p>This {@code @Service} is the layered-monolith re-expression of three CICS pseudo-conversational
 * online programs whose presentation tier (the BMS 3270 screens) has been retired in favour of a
 * stateless REST/JSON contract. Each public method ports exactly one program's business logic
 * (AAP &sect;0.4.1.3 &mdash; CardService row: "List (page 7)/view/update"):</p>
 * <table border="1">
 *   <caption>COBOL online program &rarr; service operation</caption>
 *   <tr><th>Legacy program</th><th>Function</th><th>Service method</th></tr>
 *   <tr><td>{@code app/cbl/COCRDLIC.cbl}</td>
 *       <td>List Credit Cards &mdash; browses the {@code CARDAIX} alternate index, 7 rows per
 *           screen; shows all cards for an admin with no account context, otherwise only the cards
 *           of the account carried in the {@code COMMAREA} (COCRDLIC header L4-7)</td>
 *       <td>{@link #listCards(Long, int)}</td></tr>
 *   <tr><td>{@code app/cbl/COCRDSLC.cbl}</td>
 *       <td>Card Detail View &mdash; reads a single card by its 16-digit card number</td>
 *       <td>{@link #getCard(String)}</td></tr>
 *   <tr><td>{@code app/cbl/COCRDUPC.cbl}</td>
 *       <td>Card Maintenance &mdash; field-by-field change detection then {@code REWRITE}; the card
 *           number and owning account id are <em>not</em> editable (COCRDUPC L315-L320)</td>
 *       <td>{@link #updateCard(String, CardUpdateRequest)}</td></tr>
 * </table>
 * <p>The underlying record layout for all three is the COBOL {@code CARD-RECORD} of copybook
 * {@code app/cpy/CVACT02Y.cpy} (record length 150).</p>
 *
 * <h2>Position in the layered architecture</h2>
 * <p>Strict Controller &rarr; Service &rarr; Repository &rarr; Entity flow (AAP &sect;0.3.2). This
 * service holds the business logic; it delegates all persistence to {@link CardRepository} (the
 * relational replacement for the VSAM {@code CARDDATA} KSDS and its {@code CARDAIX} alternate index)
 * and all entity&harr;DTO translation to {@link CardMapper}. It exposes only DTOs
 * ({@link CardResponse}, {@link CardListItem}, {@link PageResponse}) to its callers and never leaks a
 * {@link Card} JPA entity across the service boundary. Collaborators are supplied exclusively through
 * <strong>constructor injection</strong>, which replaces the static {@code CALL}/{@code XCTL} linkage
 * of the COBOL programs and keeps the class trivially unit-testable with mocks.</p>
 *
 * <h2>Transaction semantics</h2>
 * <p>The two read operations ({@link #listCards(Long, int)}, {@link #getCard(String)}) run inside a
 * {@code @Transactional(readOnly = true)} boundary, allowing the persistence provider to optimise for
 * read-only access; the mutating operation ({@link #updateCard(String, CardUpdateRequest)}) runs
 * inside a read-write {@code @Transactional} boundary so the load-mutate-save unit commits atomically
 * (mirroring the legacy {@code READ}&hellip;{@code UPDATE}/{@code REWRITE} guard of {@code COCRDUPC}).</p>
 *
 * <h2>Cross-cutting MUST rules (AAP &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>CVV suppression.</strong> The three-digit card verification value
 *       ({@code CARD-CVV-CD}) is persisted on the {@link Card} entity but MUST NEVER be serialized,
 *       returned, or logged. This service enforces that <em>structurally</em>: it never reads the
 *       verification-code accessor, the response DTOs declare no component for it, and
 *       {@link CardMapper} never copies it. "You cannot leak a field you never touch."</li>
 *   <li><strong>Card-identity immutability.</strong> The card number ({@code CARD-NUM}) and the
 *       owning account id ({@code CARD-ACCT-ID}) are immutable lookup keys after creation. The update
 *       path never sets them: {@link CardUpdateRequest} does not carry them, {@link CardMapper} never
 *       invokes their setters, and {@link Card#cardAcctId} is additionally mapped {@code updatable =
 *       false} at the JPA level as a second guard.</li>
 *   <li><strong>Fixed page size of 7.</strong> The legacy 3270 browse renders exactly 7 rows per
 *       screen ({@code WS-MAX-SCREEN-LINES VALUE 7} in COCRDLIC). {@link #listCards(Long, int)} sources
 *       that value from {@link CardDemoConstants#PAGE_SIZE} (== 7); it is a parity constant and is
 *       never made configurable.</li>
 * </ul>
 *
 * <p>This service is stateless and therefore thread-safe: its only fields are the two injected,
 * immutable singleton collaborators.</p>
 *
 * @see CardRepository
 * @see CardMapper
 * @see CardResponse
 * @see CardListItem
 * @see CardUpdateRequest
 * @see PageResponse
 * @see ResourceNotFoundException
 * @see CardDemoConstants#PAGE_SIZE
 */
@Service
public class CardService {

    /**
     * Spring Data repository for the {@code cards} table &mdash; the relational replacement for the
     * VSAM {@code CARDDATA} KSDS and its {@code CARDAIX} alternate index. Supplies the primary-key
     * access ({@code findById}/{@code save}), the account-filtered browse
     * ({@code findByCardAcctId(Long, Pageable)}), and the unfiltered admin browse
     * ({@code findAll(Pageable)}). Immutable, injected once at construction.
     */
    private final CardRepository cardRepository;

    /**
     * Stateless entity&harr;DTO mapper. Produces the CVV-free {@link CardResponse} /
     * {@link CardListItem} projections and applies the editable subset of a {@link CardUpdateRequest}
     * onto a managed {@link Card}. Immutable, injected once at construction.
     */
    private final CardMapper cardMapper;

    /**
     * Creates a {@code CardService} with its required collaborators.
     *
     * <p>Constructor injection (the idiomatic replacement for COBOL {@code CALL}/{@code XCTL} static
     * linkage) makes both dependencies {@code final} and mandatory, and keeps the service trivially
     * testable with mock collaborators. Spring autowires the single constructor without an explicit
     * {@code @Autowired} annotation.</p>
     *
     * @param cardRepository the card persistence gateway; must not be {@code null}
     * @param cardMapper     the entity&harr;DTO mapper; must not be {@code null}
     */
    public CardService(CardRepository cardRepository, CardMapper cardMapper) {
        this.cardRepository = cardRepository;
        this.cardMapper = cardMapper;
    }

    /**
     * Returns one page of cards, reproducing the "List Credit Cards" browse of {@code COCRDLIC}.
     *
     * <p><strong>Legacy behaviour (COCRDLIC header L4-7).</strong> The original program shows either
     * (a) <em>all</em> cards when an administrator opens the list with no account context, or (b) only
     * the cards belonging to the account carried in the CICS {@code COMMAREA} when the user is not an
     * administrator. That branch is reproduced here by the {@code accountId} argument:</p>
     * <ul>
     *   <li>when {@code accountId} is non-{@code null}, the page is filtered to that account via
     *       {@link CardRepository#findByCardAcctId(Long, Pageable)} &mdash; the relational equivalent
     *       of the VSAM {@code CARDAIX} {@code STARTBR}/{@code READNEXT} browse; and</li>
     *   <li>when {@code accountId} is {@code null} (the administrator "show all" path), the full
     *       {@code cards} table is browsed via the inherited
     *       {@link org.springframework.data.jpa.repository.JpaRepository#findAll(Pageable)
     *       findAll(Pageable)}.</li>
     * </ul>
     *
     * <p><strong>Page size (MUST be 7 &mdash; AAP &sect;0.6.4 / &sect;0.7.1).</strong> The request is
     * built as {@code PageRequest.of(page, CardDemoConstants.PAGE_SIZE)} where
     * {@link CardDemoConstants#PAGE_SIZE} is exactly {@code 7}, preserving the legacy
     * {@code WS-MAX-SCREEN-LINES VALUE 7} screen window. The returned {@link PageResponse} carries the
     * total-element count and the {@code last} flag, reproducing the COBOL {@code CA-NEXT-PAGE-EXISTS}
     * paging indicator. Each row is a CVV-free {@link CardListItem}.</p>
     *
     * @param accountId the owning account to filter by (non-administrative path); pass {@code null}
     *                  for the administrator "show all cards" browse
     * @param page      the zero-based page index to retrieve; must be {@code >= 0}
     *                  ({@link PageRequest#of(int, int)} rejects a negative index with
     *                  {@link IllegalArgumentException})
     * @return an immutable {@link PageResponse} of {@link CardListItem} rows whose {@code size} is 7,
     *         never {@code null} (possibly with empty {@code content})
     * @throws IllegalArgumentException if {@code page} is negative
     */
    @Transactional(readOnly = true)
    public PageResponse<CardListItem> listCards(Long accountId, int page) {
        // Fixed legacy screen window of 7 rows (COCRDLIC WS-MAX-SCREEN-LINES VALUE 7); sourced from
        // the single parity constant rather than a magic literal (AAP 0.6.4 / 0.7.1).
        Pageable pageable = PageRequest.of(page, CardDemoConstants.PAGE_SIZE);

        // Branch reproducing COCRDLIC's admin-vs-user view selection: a supplied account id filters
        // the CARDAIX browse to that account; its absence is the administrator "show all" full browse.
        Page<Card> result = (accountId != null)
                ? cardRepository.findByCardAcctId(accountId, pageable)
                : cardRepository.findAll(pageable);

        // Delegate the page-and-element conversion to the mapper, which emits CVV-free CardListItem
        // rows and preserves every pagination attribute (page, size==7, totalElements, first, last).
        return cardMapper.toPageResponse(result);
    }

    /**
     * Returns the detail of a single card by its 16-digit card number, reproducing the
     * "Card Detail View" of {@code COCRDSLC}.
     *
     * <p><strong>Legacy behaviour.</strong> {@code COCRDSLC} reads the {@code CARDDATA} record by its
     * primary key (the card number / RID) and renders its detail map; a missing record raises a
     * "Card not found"-style screen message. Here the lookup is the inherited
     * {@link org.springframework.data.jpa.repository.JpaRepository#findById(Object) findById(String)}
     * (the card number is the {@link Card} primary key), and a missing record is signalled by a
     * {@link ResourceNotFoundException}, which {@code GlobalExceptionHandler} translates into
     * <em>HTTP&nbsp;404&nbsp;Not&nbsp;Found</em>.</p>
     *
     * <p><strong>CVV suppression (AAP &sect;0.6.8 / &sect;0.7.1).</strong> The result is produced by
     * {@link CardMapper#toResponse(Card)}, which never reads the verification-code accessor; the
     * {@link CardResponse} contract declares no component for it, so the CVV can never reach the
     * client.</p>
     *
     * @param cardNum the 16-character card number / primary account number (PAN) to look up; the
     *                {@link Card} primary key
     * @return the {@link CardResponse} detail projection for the card (never {@code null}, CVV-free)
     * @throws ResourceNotFoundException if no card exists with the given card number (&rarr; HTTP 404)
     */
    @Transactional(readOnly = true)
    public CardResponse getCard(String cardNum) {
        // Primary-key read (COCRDSLC READ CARDDATA by RID); absence -> 404 via GlobalExceptionHandler.
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> ResourceNotFoundException.of("Card", cardNum));

        // Map to the CVV-free detail DTO; the mapper never touches the verification code.
        return cardMapper.toResponse(card);
    }

    /**
     * Applies an update to a single card, reproducing the "Card Maintenance" flow of
     * {@code COCRDUPC} while enforcing the migration's immutability and CVV-suppression rules.
     *
     * <p><strong>Legacy behaviour (COCRDUPC L315-L320).</strong> The original program loads the card
     * record, performs field-by-field change detection, and &mdash; if changes are present &mdash;
     * {@code REWRITE}s the record. Only three fields are editable on its maintenance map:
     * {@code CARD-UPDATE-EMBOSSED-NAME} (L318), {@code CARD-UPDATE-EXPIRAION-DATE} (L319), and
     * {@code CARD-UPDATE-ACTIVE-STATUS} (L320). The card number ({@code CARD-UPDATE-NUM}) and the
     * owning account id ({@code CARD-UPDATE-ACCT-ID}) are part of the record key and are never
     * rewritten.</p>
     *
     * <p><strong>Java translation.</strong> The card is loaded by primary key (404 if absent), the
     * editable subset is applied by {@link CardMapper#applyUpdate(CardUpdateRequest, Card)}, and the
     * managed entity is persisted with {@link CardRepository#save(Object) save}. The whole sequence
     * runs in a single read-write transaction so the load-mutate-save unit commits atomically,
     * mirroring the {@code READ}&hellip;{@code UPDATE}/{@code REWRITE} guard of {@code COCRDUPC}.</p>
     *
     * <p><strong>Change detection.</strong> {@code COCRDUPC}'s "no change detected" semantics are
     * reproduced idiomatically by the mapper's null-safe partial update: a {@code null} request field
     * means "leave the existing value unchanged", so a request carrying no changed fields is a no-op
     * mutation that simply re-saves the unchanged record.</p>
     *
     * <p><strong>Immutability (AAP &sect;0.6.8 / &sect;0.7.1).</strong> The card number and owning
     * account id are <em>never</em> mutated in this path: {@link CardUpdateRequest} does not carry
     * them, {@link CardMapper#applyUpdate(CardUpdateRequest, Card)} never invokes their setters, and
     * the {@code card_acct_id} column is mapped {@code updatable = false} as a defence-in-depth
     * second guard at the persistence layer.</p>
     *
     * <p><strong>CVV suppression (AAP &sect;0.6.8 / &sect;0.7.1).</strong> The verification code is
     * neither accepted from the request (the DTO has no component for it) nor returned in the response
     * (the mapper never reads it), so it can never enter or leave through this path.</p>
     *
     * @param cardNum the 16-character card number / PAN identifying the card to update; the
     *                {@link Card} primary key, supplied from the resource path and immutable
     * @param request the inbound update payload carrying the editable fields (embossed name,
     *                expiration date, active status); omitted ({@code null}) fields are left unchanged
     * @return the {@link CardResponse} reflecting the persisted state after the update (never
     *         {@code null}, CVV-free)
     * @throws ResourceNotFoundException if no card exists with the given card number (&rarr; HTTP 404)
     */
    @Transactional
    public CardResponse updateCard(String cardNum, CardUpdateRequest request) {
        // 1) Load the existing record by primary key (COCRDUPC READ ... UPDATE); absence -> 404.
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> ResourceNotFoundException.of("Card", cardNum));

        // 2) Apply ONLY the editable subset (embossedName / expirationDate / activeStatus). The mapper
        //    never sets cardNum, cardAcctId, or cvvCode -> identity immutability + CVV suppression
        //    are enforced structurally (AAP 0.6.8 / 0.7.1). Null fields are left unchanged, matching
        //    COCRDUPC's field-by-field change detection.
        cardMapper.applyUpdate(request, card);

        // 3) Persist the mutated managed entity (COCRDUPC REWRITE). save() returns the managed
        //    instance, which we map back for the response.
        Card saved = cardRepository.save(card);

        // 4) Return the CVV-free detail DTO reflecting the committed state.
        return cardMapper.toResponse(saved);
    }
}
