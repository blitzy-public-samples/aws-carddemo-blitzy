package com.carddemo.service;

import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.mapper.CardMapper;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit-card service replacing the three CICS online card programs by functional
 * domain (AAP &sect;0.4.1.1):
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (TRANID {@code 'CCLI'}) &mdash; paginated card list
 *       for an account ({@code GET /api/accounts/{acctId}/cards}); served by
 *       {@link #findByAccountId(Long, Pageable)}.</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (TRANID {@code 'CCDL'}) &mdash; single-card view
 *       ({@code GET /api/cards/{cardNum}} and the account-scoped
 *       {@code GET /api/accounts/{acctId}/cards/{cardNum}}); served by
 *       {@link #getCard(String)} and {@link #getCardForAccount(Long, String)}.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (TRANID {@code 'CCUP'}) &mdash; card update
 *       ({@code PUT /api/cards/{cardNum}}); served by {@link #updateCard(String, CardDto)}.</li>
 * </ul>
 *
 * <p><strong>VSAM &rarr; JPA mapping (AAP &sect;0.6.2).</strong> The COBOL programs read the
 * {@code CARDDAT} KSDS by card number ({@code EXEC CICS READ DATASET('CARDDAT')}), browse
 * the {@code CARDAIX} alternate-index path by account id
 * ({@code STARTBR}/{@code READNEXT}/{@code ENDBR}), and consult the {@code CARDXREF}
 * cross-reference for the account&rarr;card relationship. Those operations become,
 * respectively, {@link CardRepository#findById(Object)},
 * {@link CardRepository#findByAccountId(Long, Pageable)} (backed by the PostgreSQL B-tree
 * index {@code idx_card_account_id} that replaces {@code CARDDATA.AIX}, AAP &sect;0.6.13),
 * and {@link CardXrefRepository#findByAccountId(Long)}.</p>
 *
 * <p><strong>PR-03 &mdash; exact COBOL messages.</strong> Not-found conditions surface the
 * verbatim 88-level message literals defined in {@code app/cbl/COCRDSLC.cbl} (lines 151-154)
 * and {@code app/cbl/COCRDUPC.cbl} (lines 202-204):
 * {@value #MSG_ACCT_NOT_IN_XREF} (account absent from the cross-reference) and
 * {@value #MSG_NO_CARDS_FOR_SEARCH} (card or account/card combination not found). They are
 * carried by {@link AccountNotFoundException#withMessage(String)} (reason code 101), which
 * {@code GlobalExceptionHandler} maps to HTTP 404 and copies onto the error payload without
 * modification. The COCRDLIC "NO RECORDS FOUND FOR THIS SEARCH CONDITION" text (COCRDLIC
 * line 122) is <em>informational</em>, not an error: an account with no cards yields an
 * empty {@link CardListResponse} with HTTP 200, never a 404.</p>
 *
 * <p><strong>PR-22 &mdash; optimistic locking.</strong> {@link #updateCard(String, CardDto)}
 * re-saves a managed {@link Card} carrying a {@code @Version} column. A concurrent
 * modification raises {@code ObjectOptimisticLockingFailureException}, which
 * {@code GlobalExceptionHandler} maps to HTTP 409 Conflict &mdash; the modern, non-blocking
 * equivalent of the COCRDUPC "Record changed by some one else. Please review" path
 * ({@code app/cbl/COCRDUPC.cbl} line 208), replacing the VSAM {@code READ UPDATE} CI-level
 * exclusive lock.</p>
 *
 * <p><strong>PR-24 &mdash; transactional boundaries.</strong> Read flows run
 * {@code @Transactional(readOnly = true)}; {@link #updateCard(String, CardDto)} runs in a
 * read-write {@code @Transactional} scope bracketing the read-then-rewrite as a single
 * unit of work (the implicit CICS {@code SYNCPOINT}).</p>
 *
 * <p><strong>Security.</strong> Full card numbers (PAN) are never written to logs or
 * embedded in client-facing exception messages; only the non-sensitive account id and
 * pagination metadata are logged. The card-number path variable is treated as an opaque
 * key &mdash; it is used for {@code findById} lookups but never logged in clear.</p>
 *
 * <p><strong>PR-28 / PR-29.</strong> All imports use the {@code jakarta.*}-aligned Spring
 * stack (no {@code javax.*}); dependencies are injected by constructor over {@code final}
 * fields via Lombok {@link RequiredArgsConstructor} &mdash; no field injection.</p>
 *
 * @see com.carddemo.repository.CardRepository
 * @see com.carddemo.repository.CardXrefRepository
 * @see com.carddemo.mapper.CardMapper
 * @see com.carddemo.exception.AccountNotFoundException
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardService {

    /**
     * Exact COBOL message literal for an account/card combination (or single card) that
     * cannot be located &mdash; the {@code DID-NOT-FIND-ACCTCARD-COMBO} 88-level value in
     * {@code app/cbl/COCRDSLC.cbl} line 154 and {@code app/cbl/COCRDUPC.cbl} line 204.
     * Surfaced verbatim per PR-03.
     */
    static final String MSG_NO_CARDS_FOR_SEARCH = "Did not find cards for this search condition";

    /**
     * Exact COBOL message literal for an account that has no entry in the card
     * cross-reference &mdash; the {@code DID-NOT-FIND-ACCT-IN-CARDXREF} 88-level value in
     * {@code app/cbl/COCRDSLC.cbl} line 152 and {@code app/cbl/COCRDUPC.cbl} line 202.
     * Surfaced verbatim per PR-03.
     */
    static final String MSG_ACCT_NOT_IN_XREF = "Did not find this account in cards database";

    /**
     * Spring Data JPA repository for the card master ({@code CARDDAT}). Injected by
     * constructor (PR-29). Supplies the keyed {@link CardRepository#findById(Object)} read
     * (replacing COCRDSLC/COCRDUPC {@code EXEC CICS READ DATASET('CARDDAT')}), the
     * account-scoped {@link CardRepository#findByAccountId(Long, Pageable)} pagination
     * (replacing the COCRDLIC {@code CARDAIX} browse), and {@code save} (REWRITE).
     */
    private final CardRepository cardRepository;

    /**
     * Entity&harr;DTO mapper. Injected by constructor (PR-29). Performs CVV zero-padding,
     * expiration-date conversion, page&rarr;{@link CardListResponse} assembly, and the
     * PK-preserving {@link CardMapper#updateEntity(CardDto, Card)} mutation used by
     * {@link #updateCard(String, CardDto)}.
     */
    private final CardMapper cardMapper;

    /**
     * Spring Data JPA repository for the card cross-reference ({@code CARDXREF}). Injected
     * by constructor (PR-29). Used by {@link #getCardForAccount(Long, String)} to reproduce
     * the COCRDSLC two-stage validation: the {@link CardXrefRepository#findByAccountId(Long)}
     * lookup establishes whether the account exists in the cross-reference at all
     * ({@code DID-NOT-FIND-ACCT-IN-CARDXREF}) before the account/card combination is checked.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Lists the cards belonging to an account, paginated &mdash; the
     * {@code GET /api/accounts/{acctId}/cards} endpoint replacing {@code app/cbl/COCRDLIC.cbl}.
     *
     * <p>The COBOL forward/backward browse over the {@code CARDAIX} alternate-index path
     * ({@code STARTBR DATASET('CARDAIX')} &rarr; {@code READNEXT} for PF8 / {@code READPREV}
     * for PF7 &rarr; {@code ENDBR}) is replaced by stateless {@link Pageable} pagination
     * (AAP &sect;0.6.1): each request recomputes its window from {@code page}/{@code size},
     * with no server-side cursor lifecycle. The query is backed by {@code idx_card_account_id}.</p>
     *
     * <p>An account with no cards is <em>not</em> an error: the COCRDLIC "NO RECORDS FOUND
     * FOR THIS SEARCH CONDITION" text is informational, so this method returns an empty
     * {@link CardListResponse} (HTTP 200) rather than throwing.</p>
     *
     * @param accountId the account whose cards to list (COBOL {@code CARD-ACCT-ID PIC 9(11)});
     *                  the REST/validation layer guarantees a non-null path variable
     * @param pageable  page/size/sort instructions; typically
     *                  {@code PageRequest.of(page, 10, Sort.by("cardNum"))} to mirror the
     *                  original 10-rows-per-screen card list
     * @return a {@link CardListResponse} carrying the mapped page content and pagination
     *         metadata (an empty page when the account has no cards); never {@code null}
     */
    @Transactional(readOnly = true)
    public CardListResponse findByAccountId(Long accountId, Pageable pageable) {
        Page<Card> page = cardRepository.findByAccountId(accountId, pageable);
        log.debug("Listed {} card(s) for account {} (page {} of {})",
                page.getNumberOfElements(), accountId, page.getNumber(), page.getTotalPages());
        return cardMapper.toListResponse(page);
    }

    /**
     * Retrieves a single card by its 16-digit card number &mdash; the
     * {@code GET /api/cards/{cardNum}} endpoint replacing the card-number keyed read of
     * {@code app/cbl/COCRDSLC.cbl} ({@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(card-num)}).
     *
     * <p>An empty {@link java.util.Optional} (the COBOL {@code DFHRESP(NOTFND)} branch) raises
     * {@link AccountNotFoundException} carrying the exact COBOL message
     * {@value #MSG_NO_CARDS_FOR_SEARCH} (COCRDSLC line 154), which
     * {@code GlobalExceptionHandler} maps to HTTP 404.</p>
     *
     * @param cardNum the 16-character card number primary key
     *                ({@code CARD-NUM PIC X(16)}); must not be {@code null}
     * @return the card as a {@link CardDto}
     * @throws AccountNotFoundException if no card exists for {@code cardNum} (HTTP 404),
     *         with the exact message {@value #MSG_NO_CARDS_FOR_SEARCH}
     */
    @Transactional(readOnly = true)
    public CardDto getCard(String cardNum) {
        log.debug("Looking up card detail by card number");
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_NO_CARDS_FOR_SEARCH));
        return cardMapper.toDto(card);
    }

    /**
     * Retrieves a single card for a specific account, reproducing the COCRDSLC two-stage
     * validation &mdash; the account-scoped {@code GET /api/accounts/{acctId}/cards/{cardNum}}.
     *
     * <p>Mirrors {@code app/cbl/COCRDSLC.cbl} exactly:</p>
     * <ol>
     *   <li>{@link CardXrefRepository#findByAccountId(Long)} establishes whether the account
     *       exists in the cross-reference. An empty result is the COBOL
     *       {@code DID-NOT-FIND-ACCT-IN-CARDXREF} condition and raises
     *       {@link AccountNotFoundException} with the exact message
     *       {@value #MSG_ACCT_NOT_IN_XREF} (COCRDSLC line 152).</li>
     *   <li>The card is read by number and its {@code accountId} is checked against the
     *       requested account. A missing card, or one belonging to a different account, is the
     *       COBOL {@code DID-NOT-FIND-ACCTCARD-COMBO} condition and raises
     *       {@link AccountNotFoundException} with the exact message
     *       {@value #MSG_NO_CARDS_FOR_SEARCH} (COCRDSLC line 154).</li>
     * </ol>
     *
     * <p>Both conditions map to HTTP 404 via {@code GlobalExceptionHandler}. Runs read-only
     * (PR-24); no card number is logged in clear.</p>
     *
     * @param accountId the account that must own the card ({@code CARD-ACCT-ID PIC 9(11)});
     *                  must not be {@code null}
     * @param cardNum   the 16-character card number ({@code CARD-NUM PIC X(16)});
     *                  must not be {@code null}
     * @return the card as a {@link CardDto}
     * @throws AccountNotFoundException if the account is absent from the cross-reference
     *         ({@value #MSG_ACCT_NOT_IN_XREF}) or the account/card combination does not match
     *         ({@value #MSG_NO_CARDS_FOR_SEARCH}); HTTP 404
     */
    @Transactional(readOnly = true)
    public CardDto getCardForAccount(Long accountId, String cardNum) {
        // Stage 1 (COCRDSLC DID-NOT-FIND-ACCT-IN-CARDXREF): the account must exist in the
        // card cross-reference at all.
        List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);
        if (xrefs.isEmpty()) {
            log.debug("No cross-reference entries for account {}", accountId);
            throw AccountNotFoundException.withMessage(MSG_ACCT_NOT_IN_XREF);
        }
        // Stage 2 (COCRDSLC DID-NOT-FIND-ACCTCARD-COMBO): the card must exist and belong to
        // the requested account.
        Card card = cardRepository.findById(cardNum)
                .filter(c -> accountId.equals(c.getAccountId()))
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_NO_CARDS_FOR_SEARCH));
        return cardMapper.toDto(card);
    }

    /**
     * Updates a card's mutable attributes &mdash; the {@code PUT /api/cards/{cardNum}}
     * endpoint replacing {@code app/cbl/COCRDUPC.cbl} ({@code READ UPDATE} + {@code REWRITE}).
     *
     * <p>Reads the managed {@link Card} by number (a missing card raises
     * {@link AccountNotFoundException} with the exact COCRDUPC message
     * {@value #MSG_NO_CARDS_FOR_SEARCH}, line 204 &rarr; HTTP 404), applies the mutable
     * fields via {@link CardMapper#updateEntity(CardDto, Card)} (which deliberately never
     * touches the immutable {@code card_num} primary key), and re-saves. The
     * {@code @Version} column provides optimistic locking: a concurrent modification raises
     * {@code ObjectOptimisticLockingFailureException} &rarr; HTTP 409, the modern equivalent
     * of the COCRDUPC "Record changed by some one else. Please review" path (line 208).</p>
     *
     * <p>Runs in a read-write {@code @Transactional} scope (PR-24) so the read-then-rewrite
     * is a single unit of work. The card number is never logged in clear; only the
     * (non-sensitive) owning account id is logged.</p>
     *
     * @param cardNum the 16-character card number primary key
     *                ({@code CARD-NUM PIC X(16)}); must not be {@code null}
     * @param dto     the desired card field values; {@code accountId}, {@code cvvCode},
     *                {@code embossedName}, {@code expirationDate}, and {@code activeStatus}
     *                are applied. The {@code cardNum} on the DTO is ignored &mdash; the PK is
     *                immutable
     * @return the updated card as a {@link CardDto}
     * @throws AccountNotFoundException if no card exists for {@code cardNum} (HTTP 404),
     *         with the exact message {@value #MSG_NO_CARDS_FOR_SEARCH}
     */
    @Transactional
    public CardDto updateCard(String cardNum, CardDto dto) {
        Card existing = cardRepository.findById(cardNum)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_NO_CARDS_FOR_SEARCH));
        // PK (card_num) is immutable; updateEntity applies only the mutable fields.
        cardMapper.updateEntity(dto, existing);
        Card saved = cardRepository.save(existing);
        log.debug("Updated card belonging to account {}", saved.getAccountId());
        return cardMapper.toDto(saved);
    }
}
