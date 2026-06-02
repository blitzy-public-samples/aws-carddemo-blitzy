package com.carddemo.service;

import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.mapper.CardMapper;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit-card business service replacing the three CICS online card programs by functional
 * domain (AAP &sect;0.4.1.1):
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (TRANID {@code 'CCLI'}) &mdash; paginated card list for an
 *       account ({@code GET /api/accounts/{acctId}/cards}); served by
 *       {@link #listByAccount(Long, int, int)}. The 3270 screen rendered at most
 *       {@code WS-MAX-SCREEN-LINES = 7} rows (COCRDLIC line 177), which is preserved as the
 *       {@link #DEFAULT_PAGE_SIZE default page size}.</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (TRANID {@code 'CCDL'}) &mdash; single-card view
 *       ({@code GET /api/cards/{cardNum}}); served by {@link #getCard(String)}.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (TRANID {@code 'CCUP'}) &mdash; card update
 *       ({@code PUT /api/cards/{cardNum}}); served by {@link #updateCard(String, CardDto)}.</li>
 * </ul>
 *
 * <p><strong>VSAM &rarr; JPA mapping (AAP &sect;0.6.2).</strong> The COBOL programs read the
 * {@code CARDDAT} KSDS by card number ({@code EXEC CICS READ DATASET('CARDDAT')}), browse the
 * {@code CARDAIX} alternate-index path by account id
 * ({@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR}), and consult the
 * {@code CARDXREF} cross-reference for the account&rarr;card relationship. Those operations become,
 * respectively, {@link CardRepository#findById(Object)},
 * {@link CardRepository#findByAccountId(Long, Pageable)} (backed by the PostgreSQL B-tree index
 * {@code idx_card_account_id} that replaces {@code CARDDATA.AIX}, AAP &sect;0.6.13), and
 * {@link CardXrefRepository#findByAccountId(Long)}. The forward/backward 3270 browse cursor is
 * replaced by stateless Spring Data {@link Pageable} pagination (AAP &sect;0.6.1): each request
 * recomputes its window from {@code page}/{@code size} with no server-side cursor lifecycle.</p>
 *
 * <p><strong>PR-03 &mdash; exact COBOL messages.</strong> Validation and not-found conditions
 * surface the verbatim message literals defined in the source programs (cited per-constant below).
 * Verbatim not-found text is carried by {@link AccountNotFoundException#withMessage(String)}
 * (reason code 101 &rarr; HTTP 404), which {@code GlobalExceptionHandler} copies onto the error
 * payload without modification; field-validation failures are raised as
 * {@link IllegalArgumentException} (&rarr; HTTP 400).</p>
 *
 * <p><strong>PR-22 &mdash; optimistic locking.</strong> {@link #updateCard(String, CardDto)}
 * re-saves a managed {@link Card} carrying a {@code @Version} column. A concurrent modification
 * raises {@link ObjectOptimisticLockingFailureException}, re-thrown carrying the exact COCRDUPC
 * message {@value #MSG_OPTIMISTIC_LOCK} (COCRDUPC line 208) and mapped by
 * {@code GlobalExceptionHandler} to HTTP 409 Conflict &mdash; the modern, non-blocking equivalent
 * of the VSAM {@code READ UPDATE} CI-level exclusive lock.</p>
 *
 * <p><strong>PR-24 &mdash; transactional boundaries.</strong> Read flows run
 * {@code @Transactional(readOnly = true)}; {@link #updateCard(String, CardDto)} runs in a
 * read-write {@code @Transactional} scope that brackets the read-then-rewrite as a single unit of
 * work (the implicit CICS {@code SYNCPOINT}).</p>
 *
 * <p><strong>Security.</strong> Full card numbers (PAN) are never written to logs or embedded in
 * client-facing exception messages; only the non-sensitive account id and pagination metadata are
 * logged. The card-number path variable is treated as an opaque key &mdash; used for
 * {@code findById} lookups but never logged in clear.</p>
 *
 * <p><strong>PR-28 / PR-29.</strong> All Spring imports use the {@code jakarta.*}-aligned stack
 * (no {@code javax.*}); dependencies are injected by constructor over {@code final} fields via
 * Lombok {@link RequiredArgsConstructor} &mdash; no field injection.</p>
 *
 * @see com.carddemo.repository.CardRepository
 * @see com.carddemo.repository.CardXrefRepository
 * @see com.carddemo.mapper.CardMapper
 * @see com.carddemo.exception.InvalidCardException
 * @see com.carddemo.exception.AccountNotFoundException
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardService {

    /**
     * Default page size for {@link #listByAccount(Long, int, int)}, mirroring the COCRDLIC
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} (COCRDLIC line 177) &mdash; the maximum
     * number of card rows the original 3270 list screen could display at once. Applied whenever a
     * caller supplies a non-positive {@code size}.
     */
    private static final int DEFAULT_PAGE_SIZE = 7;

    /**
     * Card embossed-name validation pattern &mdash; alphabetic characters and spaces only. Mirrors
     * the COCRDUPC name edit that rejects any non-alphabetic/non-space content with the message
     * {@value #MSG_CARD_NAME_FORMAT} (COCRDUPC line 184).
     */
    private static final Pattern CARD_NAME_PATTERN = Pattern.compile("[A-Za-z ]+");

    /**
     * Card-number validation pattern &mdash; exactly 16 decimal digits ({@code CARD-NUM PIC X(16)}
     * holding an all-numeric PAN). Mirrors the COCRDSLC card-number edit whose failure yields
     * {@value #MSG_CARD_NUM_FORMAT} (COCRDSLC line 149).
     */
    private static final Pattern CARD_NUM_PATTERN = Pattern.compile("\\d{16}");

    // ---- Exact COBOL message literals (PR-03). Cited to the originating source line. ----

    /** COCRDSLC line 141 &mdash; card number omitted on a view request. */
    private static final String MSG_CARD_NUM_NOT_PROVIDED = "Card number not provided";

    /** COCRDSLC line 149 &mdash; supplied card number is not a 16-digit value. */
    private static final String MSG_CARD_NUM_FORMAT = "Card number if supplied must be a 16 digit number";

    /**
     * COCRDSLC line 152 / COCRDUPC line 202 &mdash; the account has no entry in the card
     * cross-reference, or the card targeted for update does not exist
     * ({@code DID-NOT-FIND-ACCT-IN-CARDXREF}).
     */
    private static final String MSG_ACCT_NOT_IN_XREF = "Did not find this account in cards database";

    /**
     * COCRDSLC line 154 &mdash; no card matches the requested key
     * ({@code DID-NOT-FIND-ACCTCARD-COMBO}).
     */
    private static final String MSG_NO_CARDS_FOR_SEARCH = "Did not find cards for this search condition";

    /** COCRDUPC line 182 &mdash; embossed name omitted on an update request. */
    private static final String MSG_CARD_NAME_NOT_PROVIDED = "Card name not provided";

    /** COCRDUPC line 184 &mdash; embossed name contains characters other than letters and spaces. */
    private static final String MSG_CARD_NAME_FORMAT = "Card name can only contain alphabets and spaces";

    /** COCRDUPC line 188 &mdash; submitted values are identical to those already on file. */
    private static final String MSG_NO_CHANGE = "No change detected with respect to values fetched.";

    /** COCRDUPC line 196 &mdash; active-status flag is neither {@code Y} nor {@code N}. */
    private static final String MSG_STATUS_YN = "Card Active Status must be Y or N";

    /** COCRDUPC line 198 &mdash; expiry month is outside the inclusive range 1..12. */
    private static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";

    /** COCRDUPC line 200 &mdash; expiry year is malformed or outside the supported range. */
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";

    /**
     * COCRDUPC line 208 &mdash; the record changed between read and rewrite
     * ({@code DATA-WAS-CHANGED-BEFORE-UPDATE}); surfaced on an optimistic-locking conflict (PR-22).
     */
    private static final String MSG_OPTIMISTIC_LOCK = "Record changed by some one else. Please review";

    /**
     * Lowest credit-card expiry year accepted by the COCRDUPC year edit (industry-standard floor).
     */
    private static final int MIN_EXPIRY_YEAR = 1950;

    /** Highest credit-card expiry year accepted by the COCRDUPC year edit. */
    private static final int MAX_EXPIRY_YEAR = 2099;

    /** Fixed external length of an ISO {@code yyyy-MM-dd} date ({@code CARD-EXPIRAION-DATE PIC X(10)}). */
    private static final int ISO_DATE_LENGTH = 10;

    /**
     * Spring Data JPA repository for the card master ({@code CARDDAT}). Injected by constructor
     * (PR-29). Supplies the account-scoped {@link CardRepository#findByAccountId(Long, Pageable)}
     * pagination (replacing the COCRDLIC {@code CARDAIX} browse), the keyed
     * {@link CardRepository#findById(Object)} read (replacing the COCRDSLC/COCRDUPC
     * {@code EXEC CICS READ DATASET('CARDDAT')}), and {@code saveAndFlush} (REWRITE) used by
     * {@link #updateCard(String, CardDto)}.
     */
    private final CardRepository cardRepository;

    /**
     * Spring Data JPA repository for the card cross-reference ({@code CARDXREF}). Injected by
     * constructor (PR-29). {@link CardXrefRepository#findByAccountId(Long)} reproduces the COCRDLIC
     * account-existence check &mdash; an account absent from the cross-reference is the COBOL
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF} condition.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Entity&harr;DTO mapper. Injected by constructor (PR-29). Performs CVV zero-padding,
     * expiration-date string conversion, and {@link Page}&rarr;{@link CardListResponse} assembly.
     */
    private final CardMapper cardMapper;

    /**
     * Lists the cards belonging to an account, paginated &mdash; the
     * {@code GET /api/accounts/{acctId}/cards} endpoint replacing {@code app/cbl/COCRDLIC.cbl}.
     *
     * <p>Reproduces the COBOL flow in two stages:</p>
     * <ol>
     *   <li><strong>Account existence (COCRDLIC {@code DID-NOT-FIND-ACCT-IN-CARDXREF}).</strong>
     *       {@link CardXrefRepository#findByAccountId(Long)} establishes whether the account is
     *       known to the card cross-reference at all. An empty result raises
     *       {@link AccountNotFoundException} with the exact message {@value #MSG_ACCT_NOT_IN_XREF}
     *       (COCRDSLC line 152 &rarr; HTTP 404).</li>
     *   <li><strong>Card window.</strong> {@link CardRepository#findByAccountId(Long, Pageable)}
     *       returns the requested page (backed by {@code idx_card_account_id}), <strong>always sorted
     *       by {@code cardNum} ascending</strong>. This service builds the {@link Pageable} with an
     *       explicit {@code Sort.by("cardNum").ascending()} so the ordering is deterministic and the
     *       page window is stable across requests &mdash; the faithful replacement for the ordered
     *       {@code CARDDATA.AIX} browse and the COCRDLIC PF7/PF8 cursor. The sort is enforced
     *       server-side and cannot be discarded regardless of how the caller (or a controller
     *       {@code @PageableDefault}) supplies the page/size arguments (review finding F2). As a
     *       defensive guard for the COCRDLIC "NO RECORDS FOUND FOR THIS SEARCH CONDITION." path
     *       (COCRDLIC line 122) &mdash; a data inconsistency where the cross-reference lists the
     *       account but the card master yields no rows on the first page &mdash; an empty first page
     *       raises {@link InvalidCardException} (code 100 &rarr; HTTP 400). Paging past the end (any
     *       page beyond the first) simply returns an empty {@link CardListResponse} with HTTP 200.</li>
     * </ol>
     *
     * @param accountId the account whose cards to list ({@code CARD-ACCT-ID PIC 9(11)}); the
     *                  REST/validation layer guarantees a non-null path variable
     * @param page      zero-based page index (the COCRDLIC PF7/PF8 scroll position)
     * @param size      requested page size; a non-positive value falls back to
     *                  {@link #DEFAULT_PAGE_SIZE} (7, the COCRDLIC {@code WS-MAX-SCREEN-LINES})
     * @return a {@link CardListResponse} carrying the mapped page content and pagination metadata;
     *         never {@code null}
     * @throws AccountNotFoundException if the account is absent from the cross-reference
     *         ({@value #MSG_ACCT_NOT_IN_XREF}; HTTP 404)
     * @throws InvalidCardException if the first page is unexpectedly empty for a cross-referenced
     *         account (HTTP 400)
     */
    @Transactional(readOnly = true)
    public CardListResponse listByAccount(Long accountId, int page, int size) {
        int pageSize = size > 0 ? size : DEFAULT_PAGE_SIZE;
        log.debug("Listing cards for account {} (page {}, size {})", accountId, page, pageSize);

        // Stage 1 (COCRDLIC DID-NOT-FIND-ACCT-IN-CARDXREF): the account must exist in the card
        // cross-reference before any card rows are returned.
        List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);
        if (xrefs.isEmpty()) {
            log.debug("No card cross-reference entries for account {}", accountId);
            throw AccountNotFoundException.withMessage(MSG_ACCT_NOT_IN_XREF);
        }

        // Stage 2: paginated browse of the card master, replacing CARDAIX STARTBR/READNEXT. The page
        // is ALWAYS sorted by cardNum ascending so the window is deterministic and stable across
        // requests — the faithful replacement for the ordered CARDDATA.AIX browse (the COCRDLIC
        // PF7/PF8 cursor). The sort is enforced here in the service so it cannot be lost regardless of
        // how the caller (or a controller default) populates the page/size arguments (F2).
        Pageable pageable = PageRequest.of(page, pageSize, Sort.by("cardNum").ascending());
        Page<Card> cardsPage = cardRepository.findByAccountId(accountId, pageable);

        // COCRDLIC "NO RECORDS FOUND FOR THIS SEARCH CONDITION." defensive guard: the
        // cross-reference lists the account yet the master returns nothing on the first page.
        if (cardsPage.isEmpty() && page == 0) {
            log.warn("Card cross-reference lists account {} but card master returned no rows", accountId);
            throw new InvalidCardException();
        }

        log.debug("Listed {} card(s) for account {} (page {} of {})",
                cardsPage.getNumberOfElements(), accountId, cardsPage.getNumber(), cardsPage.getTotalPages());
        return cardMapper.toListResponse(cardsPage);
    }

    /**
     * Retrieves a single card by its 16-digit card number &mdash; the
     * {@code GET /api/cards/{cardNum}} endpoint replacing the card-number keyed read of
     * {@code app/cbl/COCRDSLC.cbl} ({@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(card-num)}).
     *
     * <p>Reproduces the COCRDSLC input edits before the read: a missing card number yields
     * {@value #MSG_CARD_NUM_NOT_PROVIDED} (line 141) and a non-16-digit value yields
     * {@value #MSG_CARD_NUM_FORMAT} (line 149), both as {@link IllegalArgumentException}
     * (HTTP 400). An empty lookup (the COBOL {@code DFHRESP(NOTFND)} branch) raises
     * {@link AccountNotFoundException} with the exact message {@value #MSG_NO_CARDS_FOR_SEARCH}
     * (line 154 &rarr; HTTP 404).</p>
     *
     * @param cardNum the 16-character card number primary key ({@code CARD-NUM PIC X(16)})
     * @return the card as a {@link CardDto}
     * @throws IllegalArgumentException if {@code cardNum} is blank ({@value #MSG_CARD_NUM_NOT_PROVIDED})
     *         or not exactly 16 digits ({@value #MSG_CARD_NUM_FORMAT}); HTTP 400
     * @throws AccountNotFoundException if no card exists for {@code cardNum}
     *         ({@value #MSG_NO_CARDS_FOR_SEARCH}); HTTP 404
     */
    @Transactional(readOnly = true)
    public CardDto getCard(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            throw new IllegalArgumentException(MSG_CARD_NUM_NOT_PROVIDED);
        }
        if (!CARD_NUM_PATTERN.matcher(cardNum).matches()) {
            throw new IllegalArgumentException(MSG_CARD_NUM_FORMAT);
        }

        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_NO_CARDS_FOR_SEARCH));
        log.debug("Retrieved card detail for account {}", card.getAccountId());
        return cardMapper.toDto(card);
    }

    /**
     * Updates a card's mutable attributes &mdash; the {@code PUT /api/cards/{cardNum}} endpoint
     * replacing {@code app/cbl/COCRDUPC.cbl} ({@code READ UPDATE} + {@code REWRITE}).
     *
     * <p>The managed {@link Card} is read by number (a missing card raises
     * {@link AccountNotFoundException} with {@value #MSG_ACCT_NOT_IN_XREF}, COCRDUPC line 202
     * &rarr; HTTP 404). Each supplied field is then validated and compared against the value on
     * file, reproducing the COCRDUPC edits exactly:</p>
     * <ul>
     *   <li><strong>Embossed name</strong> &mdash; blank yields {@value #MSG_CARD_NAME_NOT_PROVIDED}
     *       (line 182); non-alphabetic/space content yields {@value #MSG_CARD_NAME_FORMAT}
     *       (line 184).</li>
     *   <li><strong>Active status</strong> &mdash; any value other than {@code Y}/{@code N} yields
     *       {@value #MSG_STATUS_YN} (line 196).</li>
     *   <li><strong>Expiration date</strong> &mdash; validated by {@link #validateExpirationDate(String)}
     *       (year {@value #MIN_EXPIRY_YEAR}..{@value #MAX_EXPIRY_YEAR}, month 1..12).</li>
     * </ul>
     *
     * <p>If no supplied value differs from the stored record, {@link IllegalStateException} is
     * raised with {@value #MSG_NO_CHANGE} (line 188 &rarr; HTTP 422), mirroring the COCRDUPC
     * "no change detected" path. Otherwise the entity is persisted with {@code saveAndFlush} so the
     * {@code @Version} optimistic-lock check fires synchronously within this transaction; a conflict
     * is re-thrown as {@link ObjectOptimisticLockingFailureException} carrying the exact COCRDUPC
     * message {@value #MSG_OPTIMISTIC_LOCK} (line 208 &rarr; HTTP 409, PR-22). The {@code card_num}
     * primary key is immutable and never altered.</p>
     *
     * @param cardNum the 16-character card number primary key ({@code CARD-NUM PIC X(16)})
     * @param dto     the desired field values; only {@code embossedName}, {@code activeStatus}, and
     *                {@code expirationDate} are considered. Any {@code null} field is left unchanged
     * @return the updated card as a {@link CardDto}
     * @throws AccountNotFoundException if no card exists for {@code cardNum}
     *         ({@value #MSG_ACCT_NOT_IN_XREF}; HTTP 404)
     * @throws IllegalArgumentException on any field-validation failure (HTTP 400)
     * @throws IllegalStateException if no field differs from the stored record
     *         ({@value #MSG_NO_CHANGE}; HTTP 422)
     * @throws ObjectOptimisticLockingFailureException on a concurrent modification
     *         ({@value #MSG_OPTIMISTIC_LOCK}; HTTP 409)
     */
    @Transactional
    public CardDto updateCard(String cardNum, CardDto dto) {
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_ACCT_NOT_IN_XREF));

        boolean modified = false;

        // --- Embossed name (COCRDUPC lines 182, 184) ---
        if (dto.getEmbossedName() != null) {
            String name = dto.getEmbossedName();
            if (name.isBlank()) {
                throw new IllegalArgumentException(MSG_CARD_NAME_NOT_PROVIDED);
            }
            if (!CARD_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException(MSG_CARD_NAME_FORMAT);
            }
            if (!name.equals(card.getEmbossedName())) {
                card.setEmbossedName(name);
                modified = true;
            }
        }

        // --- Active status (COCRDUPC line 196) ---
        if (dto.getActiveStatus() != null) {
            String status = dto.getActiveStatus();
            if (!"Y".equals(status) && !"N".equals(status)) {
                throw new IllegalArgumentException(MSG_STATUS_YN);
            }
            if (!status.equals(card.getActiveStatus())) {
                card.setActiveStatus(status);
                modified = true;
            }
        }

        // --- Expiration date (COCRDUPC lines 198, 200) ---
        if (dto.getExpirationDate() != null) {
            LocalDate newExpiry = validateExpirationDate(dto.getExpirationDate());
            if (!newExpiry.equals(card.getExpirationDate())) {
                card.setExpirationDate(newExpiry);
                modified = true;
            }
        }

        // COCRDUPC "no change detected" path (line 188).
        if (!modified) {
            throw new IllegalStateException(MSG_NO_CHANGE);
        }

        try {
            // saveAndFlush forces the @Version check to run synchronously inside this @Transactional
            // scope (the CICS SYNCPOINT boundary) so an optimistic-lock conflict surfaces here.
            Card saved = cardRepository.saveAndFlush(card);
            log.info("Card updated successfully for account {}", saved.getAccountId());
            return cardMapper.toDto(saved);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // COCRDUPC DATA-WAS-CHANGED-BEFORE-UPDATE (line 208 -> HTTP 409, PR-22).
            log.warn("Optimistic-lock conflict updating card for account {}", card.getAccountId());
            throw new ObjectOptimisticLockingFailureException(MSG_OPTIMISTIC_LOCK, ex);
        }
    }

    /**
     * Validates an ISO {@code yyyy-MM-dd} expiration-date string against the COCRDUPC edits and
     * returns the parsed {@link LocalDate}.
     *
     * <p>The year must fall within {@value #MIN_EXPIRY_YEAR}..{@value #MAX_EXPIRY_YEAR}
     * (else {@value #MSG_EXPIRY_YEAR}, COCRDUPC line 200) and the month within 1..12 (else
     * {@value #MSG_EXPIRY_MONTH}, COCRDUPC line 198). The year is validated before the month, and a
     * malformed or non-existent calendar date (e.g. {@code 2025-02-30}) is rejected as an invalid
     * expiry year. The COBOL field {@code CARD-EXPIRAION-DATE} retains its original (misspelled)
     * external shape {@code PIC X(10)}; per PR-14 the misspelling is not propagated to Java.</p>
     *
     * @param dateStr the candidate expiration date in {@code yyyy-MM-dd} form
     * @return the validated date as a {@link LocalDate}
     * @throws IllegalArgumentException if the value is malformed, the year is out of range
     *         ({@value #MSG_EXPIRY_YEAR}), or the month is out of range ({@value #MSG_EXPIRY_MONTH})
     */
    private LocalDate validateExpirationDate(String dateStr) {
        if (dateStr == null || dateStr.length() != ISO_DATE_LENGTH) {
            throw new IllegalArgumentException(MSG_EXPIRY_YEAR);
        }
        int year;
        int month;
        try {
            year = Integer.parseInt(dateStr.substring(0, 4));
            month = Integer.parseInt(dateStr.substring(5, 7));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(MSG_EXPIRY_YEAR, ex);
        }
        if (year < MIN_EXPIRY_YEAR || year > MAX_EXPIRY_YEAR) {
            throw new IllegalArgumentException(MSG_EXPIRY_YEAR);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException(MSG_EXPIRY_MONTH);
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException ex) {
            // Syntactically positioned digits but not a real calendar date (e.g. 2025-02-30).
            throw new IllegalArgumentException(MSG_EXPIRY_YEAR, ex);
        }
    }
}
