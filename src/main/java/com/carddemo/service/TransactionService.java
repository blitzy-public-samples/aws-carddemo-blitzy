package com.carddemo.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.transaction.TransactionDto;
import com.carddemo.dto.transaction.TransactionListResponse;
import com.carddemo.dto.transaction.TransactionRequest;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.exception.OverlimitException;
import com.carddemo.exception.TransactionValidationException;
import com.carddemo.mapper.TransactionMapper;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.util.BigDecimalUtil;
import com.carddemo.util.CardNumberMasker;
import com.carddemo.util.DateConversionUtil;
import com.carddemo.util.TransactionIdGenerator;
import com.carddemo.validation.TransactionValidator;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Transaction business service replacing {@code app/cbl/COTRN00C.cbl} (TRANID {@code 'CT00'} —
 * transaction list), {@code app/cbl/COTRN01C.cbl} (TRANID {@code 'CT01'} — transaction view), and
 * {@code app/cbl/COTRN02C.cbl} (TRANID {@code 'CT02'} — online transaction add).
 *
 * <p>The validation chain in {@link #addTransaction(TransactionRequest)} mirrors the batch program
 * {@code app/cbl/CBTRN02C.cbl} paragraph {@code 1500-VALIDATE-TRAN} (L370-L422), preserving the
 * COBOL reason codes 100/101/102/103 with their EXACT original message strings (PR-03, PR-04,
 * PR-05). Reusing the batch validation logic guarantees that online creation and the nightly
 * POSTTRAN batch reject identical inputs with identical errors:</p>
 * <ol>
 *   <li><b>100 — {@code "INVALID CARD NUMBER FOUND"}</b> ({@link InvalidCardException}, HTTP 400) —
 *       {@code 1500-A-LOOKUP-XREF} (L380-L392): the card cross-reference lookup fails.</li>
 *   <li><b>101 — {@code "ACCOUNT RECORD NOT FOUND"}</b> ({@link AccountNotFoundException}, HTTP 404) —
 *       {@code 1500-B-LOOKUP-ACCT} (L394-L416): the account lookup fails.</li>
 *   <li><b>103 — {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}</b>
 *       ({@link ExpiredAccountException}, HTTP 422) — expiration check (L417-L420).</li>
 *   <li><b>102 — {@code "OVERLIMIT TRANSACTION"}</b> ({@link OverlimitException}, HTTP 422) —
 *       credit-limit check (L393-L422).</li>
 * </ol>
 *
 * <p><b>Validation order (PR-03).</b> The COBOL paragraph evaluates the credit-limit test (102)
 * textually before the expiration test (103) within {@code 1500-B-LOOKUP-ACCT}, but because each
 * failing test overwrites {@code WS-VALIDATION-FAIL-REASON}, the expiration result <em>wins</em>
 * when both conditions fail. This service reproduces that net behaviour by throwing the expiration
 * exception (103) <em>before</em> the overlimit exception (102): with short-circuit throwing, an
 * input that is both expired and overlimit surfaces code 103, exactly as in COBOL.</p>
 *
 * <p><b>Cardholder identification (COTRN02C {@code VALIDATE-INPUT-KEY-FIELDS}, L193-L230).</b> The
 * original screen accepts <em>either</em> an account id or a card number and resolves the missing
 * one through the cross-reference. This service preserves that behaviour: when the request supplies
 * a card number it is used directly for the cross-reference lookup; otherwise the card is resolved
 * from the mandatory account id via {@link CardXrefRepository#findByAccountId(Long)}.</p>
 *
 * <p><b>Posting is deferred to batch (Phase 7 / AAP &sect;0.6.6).</b> Faithful to {@code COTRN02C},
 * online creation ONLY validates and inserts the {@code TRAN-RECORD}; it does <em>not</em> upsert
 * {@code TCATBAL} (PR-06) nor apply the sign-based account-balance bucket update (PR-07). Those are
 * performed by the POSTTRAN batch job ({@code CBTRN02C}: {@code 2700-UPDATE-TCATBAL} and
 * {@code 2800-UPDATE-ACCOUNT-REC}). The {@code transactionTypeRepository},
 * {@code transactionCategoryRepository}, and {@code tcatBalRepository} dependencies are wired for
 * that batch-parity surface and reference-data access.</p>
 *
 * <p><b>Transaction IDs (PR-10)</b> are 16 characters formed as {@code parmDate(10) + suffix(6)} via
 * {@link TransactionIdGenerator}; <b>timestamps (PR-11)</b> use the 26-character DB2 external format
 * {@code yyyy-MM-dd-HH.mm.ss.SS'0000'} produced by {@link DateConversionUtil} at the I/O boundary
 * and normalized to {@code LocalDateTime} for storage. <b>All monetary arithmetic (PR-16)</b> uses
 * {@code BigDecimal} with scale 2 and {@link java.math.RoundingMode#HALF_UP} via
 * {@link BigDecimalUtil}, compared with {@code BigDecimal.compareTo} (never {@code equals}); the
 * shared {@link TransactionValidator} chain performs the credit-limit comparison.
 * {@code float}/{@code double} are forbidden.</p>
 *
 * <p>Read methods are {@code @Transactional(readOnly = true)}; {@link #addTransaction} runs in a
 * read-write {@code @Transactional} scope that brackets the implicit CICS {@code SYNCPOINT}
 * unit-of-work (PR-24). Dependencies are injected via the constructor generated by Lombok
 * {@code @RequiredArgsConstructor} (PR-29); no field injection is used.</p>
 *
 * @see com.carddemo.batch.TransactionPostingJobConfig
 * @see com.carddemo.controller.TransactionController
 * @see com.carddemo.mapper.TransactionMapper
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    /** Length of the natural transaction-id primary key ({@code TRAN-ID PIC X(16)}). */
    private static final int TRAN_ID_LENGTH = 16;

    /** Number of leading characters of a DB2 timestamp that form the {@code YYYY-MM-DD} date. */
    private static final int DATE_PREFIX_LENGTH = 10;

    /**
     * Posted-transaction repository ({@code TRANSACT}). Backs the list browse
     * ({@code findAll(Pageable)}), single view ({@code findById}), online insert ({@code save}),
     * and the card-keyed query ({@code findByCardNum}).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Card cross-reference repository ({@code CXREF}). {@code findById(cardNumber)} reproduces the
     * keyed {@code READ XREF-FILE} of {@code CBTRN02C} {@code 1500-A-LOOKUP-XREF} (code 100), and
     * {@code findByAccountId} resolves the card number when only an account id is supplied.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Shared transaction validation chain ({@code CBTRN02C} {@code 1500-VALIDATE-TRAN}). The online
     * add path delegates the account lookup (code 101) and the account-level credit-limit (code
     * 102) and expiration (code 103) checks to this single component so it runs the IDENTICAL
     * chain as the batch POSTTRAN processor &mdash; no duplicated repository or money/date
     * arithmetic. The validator internally owns the {@code AccountRepository}; this service no
     * longer reads accounts directly.
     */
    private final TransactionValidator transactionValidator;

    /**
     * Transaction-type reference-data repository. Wired for CBTRN02C-parity / reference-data access;
     * the online add path does not post, so it is reserved for the batch posting surface.
     */
    private final TransactionTypeRepository transactionTypeRepository;

    /**
     * Transaction-category reference-data repository. Wired for CBTRN02C-parity / reference-data
     * access; the online add path does not post, so it is reserved for the batch posting surface.
     */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Transaction-category-balance repository ({@code TCATBAL}, composite key). Reserved for the
     * batch posting path ({@code CBTRN02C} {@code 2700-UPDATE-TCATBAL}); the online add path does
     * not upsert category balances (PR-06).
     */
    private final TransactionCategoryBalanceRepository tcatBalRepository;

    /** Generator for the 16-character {@code parmDate(10) + suffix(6)} transaction id (PR-10). */
    private final TransactionIdGenerator transactionIdGenerator;

    /** Entity&harr;DTO mapper handling DB2 timestamp formatting and {@code cardNum}/{@code cardNumber} translation. */
    private final TransactionMapper transactionMapper;

    /**
     * Lists posted transactions with stateless pagination and optional account / card filtering —
     * the REST equivalent of {@code COTRN00C} ({@code STARTBR}/{@code READNEXT} browse, TRANID
     * {@code 'CT00'}). The original PF7/PF8 cursor is replaced by a Spring Data {@link Pageable};
     * each request recomputes its page independently (AAP &sect;0.6.1).
     *
     * <p><b>Filter resolution (matches the COTRN00C key fields).</b> The legacy screen let the
     * operator narrow the browse by card number or account id; this method reproduces that with a
     * single, deterministic precedence:</p>
     * <ol>
     *   <li><b>{@code cardNumber} supplied</b> (non-blank) &rarr; the listing is restricted to that
     *       card via {@link TransactionRepository#findByCardNum(String, Pageable)} (entity field
     *       {@code cardNum}, column {@code card_num}).</li>
     *   <li><b>else {@code accountId} supplied</b> &rarr; the account's cards are resolved through
     *       the {@code CARDXREF} cross-reference ({@link CardXrefRepository#findByAccountId(Long)},
     *       the {@code CXACAIX} alternate-index path) and the listing is restricted to those cards
     *       via {@link TransactionRepository#findByCardNumIn(java.util.Collection, Pageable)}. An
     *       account that owns no cards short-circuits to an empty page (never an empty {@code IN ()}
     *       predicate).</li>
     *   <li><b>else</b> &rarr; the unfiltered full browse via
     *       {@link TransactionRepository#findAll(Pageable)}.</li>
     * </ol>
     *
     * <p>Card-number filtering is the surface exercised by {@code GET /api/transactions?cardNumber=...};
     * account filtering by {@code GET /api/transactions?accountId=...}. The card number is masked to
     * its last four digits before logging (CP4 / PCI hygiene) via {@link CardNumberMasker}.</p>
     *
     * @param accountId  optional 11-digit account-id filter ({@code ACCT-ID}); {@code null} when not
     *                   supplied. Ignored when {@code cardNumber} is supplied.
     * @param cardNumber optional 16-digit card-number filter ({@code TRAN-CARD-NUM}); {@code null}
     *                   or blank when not supplied. Takes precedence over {@code accountId}.
     * @param pageable   the page request (page number, size, and sort) supplied by the controller
     * @return a {@link TransactionListResponse} carrying the page content and pagination metadata
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(Long accountId, String cardNumber, Pageable pageable) {
        // SECURITY (CP4): mask the PAN before logging — only the last 4 digits appear.
        log.debug("Listing transactions accountId={} cardNumber={} page={} size={}",
            accountId, CardNumberMasker.mask(cardNumber),
            pageable.getPageNumber(), pageable.getPageSize());

        Page<Transaction> page;
        if (cardNumber != null && !cardNumber.isBlank()) {
            // COTRN00C browse narrowed to a single card number.
            page = transactionRepository.findByCardNum(cardNumber, pageable);
        } else if (accountId != null) {
            // COTRN00C browse narrowed to a single account: resolve the account's card
            // numbers through the CARDXREF cross-reference (CXACAIX), then enumerate the
            // transactions for those cards. No cards -> empty page (avoid a degenerate IN ()).
            List<String> cardNums = cardXrefRepository.findByAccountId(accountId).stream()
                .map(CardXref::getXrefCardNum)
                .toList();
            page = cardNums.isEmpty()
                ? Page.<Transaction>empty(pageable)
                : transactionRepository.findByCardNumIn(cardNums, pageable);
        } else {
            // Unfiltered full browse.
            page = transactionRepository.findAll(pageable);
        }
        return transactionMapper.toListResponse(page);
    }

    /**
     * Lists posted transactions with stateless pagination and no filtering — a convenience overload
     * delegating to {@link #listTransactions(Long, String, Pageable)} with both filters
     * {@code null}. Retained for callers (and historical wiring) that page the full {@code TRANSACT}
     * browse without narrowing by account or card.
     *
     * @param pageable the page request (page number, size, and sort) supplied by the controller
     * @return a {@link TransactionListResponse} carrying the page content and pagination metadata
     */
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(Pageable pageable) {
        return listTransactions(null, null, pageable);
    }

    /**
     * Retrieves a single transaction by its 16-character id — the REST equivalent of
     * {@code COTRN01C} (transaction view). Reproduces the COBOL {@code STARTBR}/key-validation flow:
     * an id of the wrong length is rejected up front, and a missing record surfaces the original
     * {@code COTRN02C} message {@code "Transaction ID NOT found..."}.
     *
     * <p>Consistent with the sibling read endpoints (accounts, cards, customers), a syntactically
     * valid 16-character id that matches no record is reported as a not-found condition via
     * {@link AccountNotFoundException} (which {@code GlobalExceptionHandler} maps to HTTP 404),
     * whereas a {@code null} or wrong-length id remains a malformed-input error via
     * {@link IllegalArgumentException} (mapped to HTTP 400). The verbatim
     * {@link AccountNotFoundException#withMessage(String)} factory preserves the exact COBOL
     * {@code COTRN02C} message on the REST error payload.</p>
     *
     * @param tranId the 16-character transaction id
     * @return the {@link TransactionDto} for the requested transaction
     * @throws IllegalArgumentException  if {@code tranId} is {@code null} or not exactly 16 characters
     *                                   (malformed input &rarr; HTTP 400)
     * @throws AccountNotFoundException  if {@code tranId} is well-formed but does not correspond to
     *                                   an existing transaction (not found &rarr; HTTP 404)
     */
    @Transactional(readOnly = true)
    public TransactionDto getTransaction(String tranId) {
        log.debug("Looking up transaction {}", tranId);
        if (tranId == null || tranId.length() != TRAN_ID_LENGTH) {
            throw new IllegalArgumentException("Tran ID must be 16 characters");
        }
        Transaction transaction = transactionRepository.findById(tranId)
            .orElseThrow(() -> AccountNotFoundException.withMessage("Transaction ID NOT found..."));
        return transactionMapper.toDto(transaction);
    }

    /**
     * Creates a new transaction online — the REST equivalent of {@code COTRN02C}
     * ({@code ADD-TRANSACTION}). Runs the {@code CBTRN02C} {@code 1500-VALIDATE-TRAN} validation
     * chain (card&rarr;account&rarr;expiration&rarr;overlimit), generates the 16-character id
     * (PR-10), stamps the 26-character DB2 timestamps (PR-11), and inserts the {@code TRAN-RECORD}.
     * Posting (TCATBAL upsert, account-balance bucket update) is intentionally deferred to the
     * POSTTRAN batch job (Phase 7 / AAP &sect;0.6.6).
     *
     * @param request the inbound transaction request (validated at the controller via {@code @Valid})
     * @return the persisted transaction as a {@link TransactionDto}
     * @throws IllegalArgumentException        if a required field is missing
     *                                         (see {@link #validateInputFields(TransactionRequest)})
     * @throws InvalidCardException            code 100 — an explicitly supplied card number has no
     *                                         cross-reference (HTTP 400)
     * @throws TransactionValidationException  HTTP 400 — both {@code accountId} and {@code cardNumber}
     *                                         were supplied but the card does not belong to that
     *                                         account (QA Issue 3; PAN-free message)
     * @throws AccountNotFoundException        code 101 — the account lookup fails, OR an
     *                                         {@code accountId}-only request references an account
     *                                         that has no card cross-reference (QA Issue 5; HTTP 404)
     * @throws ExpiredAccountException         code 103 — the account expired before the transaction date
     * @throws OverlimitException              code 102 — the projected balance exceeds the credit limit
     */
    @Transactional
    public TransactionDto addTransaction(TransactionRequest request) {
        // SECURITY (CP4): never log the full PAN. accountId is a non-sensitive
        // surrogate; the card number is masked to its last 4 digits via
        // CardNumberMasker before it can reach any appender.
        log.info("Adding transaction for accountId={} cardNumber={}",
            request.getAccountId(), CardNumberMasker.mask(request.getCardNumber()));

        // PHASE A — field-level validation (COTRN02C input validation).
        validateInputFields(request);

        // PHASE B — validation chain (mirrors CBTRN02C 1500-VALIDATE-TRAN).

        // Step 1: card cross-reference lookup (CBTRN02C 1500-A-LOOKUP-XREF, L380-L392) -> code 100.
        // COTRN02C VALIDATE-INPUT-KEY-FIELDS resolves the cardholder by card number OR account id;
        // either route ultimately yields the cross-reference record (and thus the account id).
        // accountId is the PRIMARY/authoritative identifier (TransactionRequest DTO contract:
        // "If both provided, accountId takes precedence"); cardNumber is an optional alternative.
        String requestedCardNumber = request.getCardNumber();
        Long requestedAccountId = request.getAccountId();
        CardXref xref;
        if (requestedCardNumber != null && !requestedCardNumber.isBlank()) {
            // Card number supplied: resolve the cross-reference by card number. A miss is an
            // invalid explicit card number -> code 100 / HTTP 400 (unchanged, preserves PR-03).
            xref = cardXrefRepository.findById(requestedCardNumber)
                .orElseThrow(InvalidCardException::new);
            // QA Issue 3 — consistency check. When BOTH accountId and cardNumber are supplied they
            // must agree: accountId is authoritative, so a card that does not belong to the supplied
            // account is a deterministic, PAN-free validation failure (-> HTTP 400 via the
            // TransactionValidationException fallback handler, code 0). This closes the gap where the
            // supplied accountId was silently ignored and a transaction was created against the card
            // regardless of the mismatch. (requestedAccountId may be null when only a card number was
            // supplied; in that card-only case there is nothing to reconcile and the check is skipped.)
            if (requestedAccountId != null
                && !requestedAccountId.equals(xref.getAccountId())) {
                throw new TransactionValidationException(
                    "Card number does not belong to the supplied account id");
            }
        } else {
            // Account-id-only path: resolve the cardholder's card via the account cross-reference.
            // QA Issue 5 — an existing account that has no card cross-reference (or simply no card)
            // is a NOT-FOUND condition, not an invalid-card condition. Surface HTTP 404 with a
            // domain-accurate message (AccountNotFoundException carries COBOL code 101 -> 404) rather
            // than the previous InvalidCardException (code 100 / HTTP 400), so the three cases
            // (invalid explicit card, missing account-card xref, missing account) are distinguishable.
            xref = cardXrefRepository.findByAccountId(requestedAccountId).stream()
                .findFirst()
                .orElseThrow(() -> AccountNotFoundException.withMessage(
                    "No card found for account: " + requestedAccountId));
        }
        String resolvedCardNumber = xref.getXrefCardNum();

        // Determine the originating timestamp (PR-11): use the supplied 26-char DB2 value, else now.
        String origTimestamp = (request.getOrigTimestamp() != null
                && !request.getOrigTimestamp().isBlank())
            ? request.getOrigTimestamp()
            : DateConversionUtil.nowAsDb2Timestamp();
        // The COBOL expiration test compares against DALYTRAN-ORIG-TS(1:10) — the leading
        // YYYY-MM-DD, which is also the 10-char PARM-DATE prefix of the generated id (PR-10).
        String tranDatePart = origTimestamp.length() >= DATE_PREFIX_LENGTH
            ? origTimestamp.substring(0, DATE_PREFIX_LENGTH)
            : DateConversionUtil.todayAsIso();

        // Steps 2-4: account lookup (code 101), credit-limit (code 102) and expiration (code 103)
        // are delegated VERBATIM to the shared TransactionValidator (CBTRN02C 1500-B-LOOKUP-ACCT),
        // so the online add path and the batch POSTTRAN path run the IDENTICAL chain — same exact
        // COBOL messages, same strict 100->101->102->103 ordering, and the same both-fail
        // precedence (103 wins, via AccountValidator's last-writer-wins logic). Build the
        // DALYTRAN-equivalent candidate carrying the resolved card number, the scaled amount
        // (PR-16) and the originating timestamp; the validator's internal xref re-read on the
        // already-resolved card succeeds, so the only failures it can raise here are 101/102/103.
        DailyTransaction candidate = new DailyTransaction();
        candidate.setCardNum(resolvedCardNumber);
        candidate.setAmount(BigDecimalUtil.ensureScaleTwo(request.getAmount()));
        candidate.setOrigTimestamp(DateConversionUtil.fromDb2Timestamp(origTimestamp));
        transactionValidator.validate(candidate);

        // PHASE C — build and persist the transaction record.
        // The mapper translates cardNumber->cardNum, scales the amount, and parses the request
        // timestamp; the service supplies the server-controlled fields (resolved card number,
        // generated id, and the originating/processing timestamps).
        Transaction transaction = transactionMapper.toEntity(request);
        transaction.setCardNum(resolvedCardNumber);
        // PR-10 / AAP §0.6.10: the ONLINE path draws its 6-digit suffix from the PostgreSQL
        // sequence transaction_id_seq — unique across concurrent requests and monotonic across
        // restarts — NOT from the batch in-memory AtomicLong (reserved for INTCALC/CBACT04C
        // interest postings, which would reset on restart and could collide for the same date
        // prefix). nextOnlineId composes parmDate(10) + suffix(6) and validates the 16-char width.
        long onlineSuffix = transactionRepository.nextTransactionIdSuffix();
        transaction.setTranId(transactionIdGenerator.nextOnlineId(tranDatePart, onlineSuffix)); // PR-10
        transaction.setOrigTimestamp(DateConversionUtil.fromDb2Timestamp(origTimestamp)); // PR-11
        transaction.setProcTimestamp(
            DateConversionUtil.fromDb2Timestamp(DateConversionUtil.nowAsDb2Timestamp())); // PR-11

        Transaction saved = transactionRepository.save(transaction);
        log.info("Transaction added successfully. Your Tran ID is {}", saved.getTranId());
        return transactionMapper.toDto(saved);
    }

    /**
     * Returns every posted transaction for a given card number, ordered as the repository returns
     * them — a helper supporting card-scoped reporting and UI flows.
     *
     * @param cardNumber the 16-character card number ({@code TRAN-CARD-NUM})
     * @return an immutable list of {@link TransactionDto} (never {@code null}; empty when none match)
     */
    @Transactional(readOnly = true)
    public List<TransactionDto> findByCardNum(String cardNumber) {
        // SECURITY (CP4): mask the PAN before logging — only the last 4 digits appear.
        log.debug("Listing transactions for card {}", CardNumberMasker.mask(cardNumber));
        return transactionMapper.toDtoList(transactionRepository.findByCardNum(cardNumber));
    }

    /**
     * Field-level required-input validation for online transaction creation, mirroring the
     * {@code COTRN02C} {@code VALIDATE-INPUT-DATA-FIELDS} screen-edit pass. Each missing field
     * raises an {@link IllegalArgumentException} (mapped to HTTP 400 by the global handler) whose
     * message matches the field-validation text specified by the migration plan.
     *
     * <p>This is defence-in-depth: the same constraints are declared on {@link TransactionRequest}
     * via Jakarta Bean Validation and enforced by {@code @Valid} at the controller, but the service
     * re-checks so that direct (non-HTTP) callers receive the same guarantees.</p>
     *
     * @param request the inbound request to validate
     * @throws IllegalArgumentException if any required field is {@code null} or blank
     */
    private void validateInputFields(TransactionRequest request) {
        // The originating timestamp is optional per the DTO contract (it defaults to "now" in
        // addTransaction); however an explicitly-supplied-but-blank value is rejected with the
        // COBOL date-edit message.
        if (request.getOrigTimestamp() != null && request.getOrigTimestamp().isBlank()) {
            throw new IllegalArgumentException(
                "Date in CCYY-MM-DD format must be supplied...");
        }
        if (request.getAmount() == null) {
            throw new IllegalArgumentException(
                "Amount in -99999999.99 format must be supplied...");
        }
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            throw new IllegalArgumentException("Description must be supplied...");
        }
        if (request.getTypeCd() == null || request.getTypeCd().isBlank()) {
            throw new IllegalArgumentException("Type CD must be supplied...");
        }
        if (request.getCategoryCd() == null || request.getCategoryCd().isBlank()) {
            throw new IllegalArgumentException("Category CD must be supplied...");
        }
        if (request.getSource() == null || request.getSource().isBlank()) {
            throw new IllegalArgumentException("Tran. Source must be supplied...");
        }
        if ((request.getCardNumber() == null || request.getCardNumber().isBlank())
            && request.getAccountId() == null) {
            throw new IllegalArgumentException(
                "Account ID OR Card Number must be supplied...");
        }
        if (request.getMerchantId() == null) {
            throw new IllegalArgumentException("Merchant ID must be supplied...");
        }
    }
}
