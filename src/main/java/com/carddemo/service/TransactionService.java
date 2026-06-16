package com.carddemo.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionResponse;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategory;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.mapper.TransactionMapper;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;
import com.carddemo.util.TranIdGenerator;

/**
 * Transaction business-logic service &mdash; the layered re-expression of the three legacy CICS
 * online transaction programs of AWS CardDemo:
 *
 * <ul>
 *   <li><b>{@code COTRN00C}</b> &mdash; <em>list</em> transactions for an account
 *       ({@link #listTransactions(Long, int)}). The 3270 browse over the {@code TRANSACT.AIX}
 *       alternate index (in origination-timestamp order) is replaced by a paginated, ordered
 *       repository query. The legacy fixed screen page size of <strong>seven</strong> rows is
 *       preserved via {@link CardDemoConstants#PAGE_SIZE} (AAP &sect;0.6.4 / &sect;0.7.1).</li>
 *   <li><b>{@code COTRN01C}</b> &mdash; <em>view</em> a single transaction by id
 *       ({@link #getTransaction(String)}). Reproduces the program's "Tran ID can NOT be
 *       empty&hellip;" empty-input guard and its "&hellip; NOT found" path (HTTP&nbsp;404).</li>
 *   <li><b>{@code COTRN02C}</b> &mdash; <em>add</em> a transaction
 *       ({@link #addTransaction(TransactionAddRequest)}). Reproduces the
 *       <em>Account&nbsp;ID&nbsp;XOR&nbsp;Card&nbsp;Number</em> key rule, the card cross-reference
 *       resolution, the {@code CSUTLDTC} origination/processing date validation, and the
 *       {@code STARTBR}/{@code READPREV} next-id scheme (now a database sequence). The screen-only
 *       Y/N confirm field has no REST analog and is intentionally not modeled.</li>
 * </ul>
 *
 * <h2>Layering &amp; transactions</h2>
 * <p>This {@code @Service} holds the business logic of the Controller&nbsp;&rarr;&nbsp;Service&nbsp;&rarr;
 * Repository&nbsp;&rarr;&nbsp;Entity stack: controllers never touch repositories directly, and the
 * persisted {@link Transaction} entity never leaves this boundary &mdash; the
 * {@link TransactionMapper} converts to/from the immutable REST DTOs. The two read operations are
 * annotated {@code @Transactional(readOnly = true)} (no flush, read-optimized) and the write
 * operation {@code @Transactional} so a failure anywhere in the
 * resolve&nbsp;&rarr;&nbsp;validate&nbsp;&rarr;&nbsp;persist sequence rolls the unit of work back
 * atomically.</p>
 *
 * <h2>Collaborators</h2>
 * <p>Genuine Spring beans are supplied by <strong>constructor injection</strong> (immutable
 * {@code final} fields, no field injection):</p>
 * <ul>
 *   <li>{@link TransactionRepository} &mdash; the {@code TRANSACT} data-access boundary
 *       ({@code findByAcctIdOrderByOrigTs}, inherited {@code findById}/{@code save}).</li>
 *   <li>{@link CardXrefRepository} &mdash; the card&nbsp;&rarr;&nbsp;account cross-reference
 *       ({@code findByXrefCardNum} for the card key path, {@code findByXrefAcctId} for the
 *       account key path).</li>
 *   <li>{@link AccountRepository} &mdash; confirms account existence on the account key path.</li>
 *   <li>{@link TransactionMapper} &mdash; entity&harr;DTO boundary mapper.</li>
 *   <li>{@link TranIdGenerator} &mdash; the 16-character zero-padded id generator
 *       (<strong>online add path ONLY</strong>, AAP &sect;0.6.5).</li>
 *   <li>{@link DateValidationService} &mdash; the {@code CSUTLDTC} date-validation parity.</li>
 * </ul>
 * <p>The two foundational constants holders are referenced <em>statically</em> rather than injected:
 * {@link CardDemoConstants} (a non-instantiable {@code final} class) supplies
 * {@link CardDemoConstants#PAGE_SIZE}, and {@link MessageService} (a static-constant holder in this
 * same package) supplies the parity message text. Referencing them statically is the idiomatic,
 * warning-free usage for constant holders and avoids an unread injected field.</p>
 *
 * <h2>Parity-critical rules enforced here</h2>
 * <ul>
 *   <li><b>Dual timestamps.</b> Both {@code origTs} and {@code procTs} are stamped and persisted
 *       (AAP &sect;0.7.3 #10); the validated dates become {@link LocalDate#atStartOfDay()}
 *       {@link LocalDateTime}s.</li>
 *   <li><b>Online-only id generation.</b> {@link TranIdGenerator} is used exclusively on the add
 *       path; it is never reused by batch jobs.</li>
 *   <li><b>Fixed page size 7</b> on the list path.</li>
 *   <li><b>Money precision.</b> The amount is carried as {@link java.math.BigDecimal} end-to-end by
 *       the mapper/entity ({@code NUMERIC(12,2)}); no lossy conversion occurs in this service.</li>
 * </ul>
 *
 * @see <a href="file:app/cbl/COTRN00C.cbl">COTRN00C.cbl</a> (list)
 * @see <a href="file:app/cbl/COTRN01C.cbl">COTRN01C.cbl</a> (view)
 * @see <a href="file:app/cbl/COTRN02C.cbl">COTRN02C.cbl</a> (add)
 */
@Service
public class TransactionService {

    /** {@code TRANSACT} data-access boundary (list/view/add). */
    private final TransactionRepository transactionRepository;

    /** Card&nbsp;&rarr;&nbsp;customer&nbsp;&rarr;&nbsp;account cross-reference access. */
    private final CardXrefRepository cardXrefRepository;

    /** Account existence verification for the account-key add path. */
    private final AccountRepository accountRepository;

    /**
     * Transaction type/category reference-data access. Used on the add path to pre-validate that the
     * supplied {@code (typeCd, categoryCd)} pair exists in {@code transaction_category} <em>before</em>
     * the insert, so a missing reference is reported as a clean HTTP&nbsp;404 (mirroring the
     * account/card not-found paths) instead of letting the {@code fk_tran_cat} foreign key
     * (AAP &sect;0.3.1) fail at flush and surface as an unhandled HTTP&nbsp;500.
     */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /** Entity&harr;DTO boundary mapper. */
    private final TransactionMapper transactionMapper;

    /** Online-add-only 16-character transaction id generator. */
    private final TranIdGenerator tranIdGenerator;

    /** {@code CSUTLDTC} date-validation parity service. */
    private final DateValidationService dateValidationService;

    /**
     * Constructs the service with all of its collaborators. Constructor injection keeps every
     * dependency {@code final} (immutable, fully initialized, trivially testable) and is the
     * Spring re-expression of the COBOL {@code CALL}/{@code XCTL} static linkage between programs.
     *
     * @param transactionRepository         the transaction data-access repository; must not be {@code null}
     * @param cardXrefRepository            the card cross-reference repository; must not be {@code null}
     * @param accountRepository             the account repository; must not be {@code null}
     * @param transactionCategoryRepository the transaction type/category reference repository, used to
     *                                      pre-validate the {@code (typeCd, categoryCd)} pair on the add
     *                                      path; must not be {@code null}
     * @param transactionMapper             the entity&harr;DTO mapper; must not be {@code null}
     * @param tranIdGenerator               the online transaction-id generator; must not be {@code null}
     * @param dateValidationService         the date-validation service; must not be {@code null}
     */
    public TransactionService(
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionCategoryRepository transactionCategoryRepository,
            TransactionMapper transactionMapper,
            TranIdGenerator tranIdGenerator,
            DateValidationService dateValidationService) {
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
        this.transactionMapper = transactionMapper;
        this.tranIdGenerator = tranIdGenerator;
        this.dateValidationService = dateValidationService;
    }

    /**
     * Lists the transactions of a single account, one fixed page of seven rows at a time, ordered
     * by origination timestamp ascending &mdash; the {@code COTRN00C} "List Transactions" browse.
     *
     * <p>The legacy {@code STARTBR}/{@code READNEXT}/{@code READPREV} walk of the {@code TRANSACT.AIX}
     * alternate index is replaced by the index-supported derived query
     * {@link TransactionRepository#findByAcctIdOrderByOrigTs(Long, org.springframework.data.domain.Pageable)},
     * paged with {@link CardDemoConstants#PAGE_SIZE} (= 7) to preserve the fixed 3270 screen size.
     * The returned {@link PageResponse} carries the page metadata (totals, first/last flags) that
     * reproduce the program's forward/backward paging indicators.</p>
     *
     * @param accountId the owning account identifier whose transactions are listed
     * @param page      the zero-based page index to retrieve
     * @return an immutable {@link PageResponse} of {@link TransactionListItem} rows for the account;
     *         never {@code null} (an empty page is returned when the account has no transactions or
     *         the requested page is beyond the available rows)
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionListItem> listTransactions(Long accountId, int page) {
        Page<Transaction> result = transactionRepository.findByAcctIdOrderByOrigTs(
                accountId, PageRequest.of(page, CardDemoConstants.PAGE_SIZE));
        return transactionMapper.toPageResponse(result);
    }

    /**
     * Retrieves a single transaction by its 16-character identifier &mdash; the {@code COTRN01C}
     * "View Transaction" path.
     *
     * <p>Mirrors the program's two guards: a missing/blank id reproduces the
     * "Tran ID can NOT be empty&hellip;" validation message ({@link MessageService#TRAN_ID_REQUIRED},
     * HTTP&nbsp;400), and an unknown id reproduces the "&hellip; NOT found" path as a
     * {@link ResourceNotFoundException} (HTTP&nbsp;404). On success the full detail DTO is returned,
     * carrying <strong>both</strong> the origination and processing timestamps.</p>
     *
     * @param tranId the 16-character transaction identifier to view
     * @return the {@link TransactionResponse} detail DTO for the transaction (both timestamps)
     * @throws ValidationException       if {@code tranId} is {@code null} or blank (HTTP&nbsp;400)
     * @throws ResourceNotFoundException if no transaction exists with the supplied id (HTTP&nbsp;404)
     */
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String tranId) {
        // COTRN01C: "Tran ID can NOT be empty..." when the input key field is empty.
        if (tranId == null || tranId.isBlank()) {
            throw new ValidationException(MessageService.TRAN_ID_REQUIRED);
        }
        Transaction tx = transactionRepository.findById(tranId)
                .orElseThrow(() -> ResourceNotFoundException.of("Transaction", tranId));
        return transactionMapper.toResponse(tx);
    }

    /**
     * Adds a new transaction &mdash; the {@code COTRN02C} "Add Transaction" path.
     *
     * <p>The processing sequence faithfully reproduces the COBOL paragraph order:</p>
     * <ol>
     *   <li><b>Key rule (Account&nbsp;ID&nbsp;XOR&nbsp;Card&nbsp;Number).</b> {@code COTRN02C}
     *       requires <em>exactly one</em> of an account id or a card number
     *       ("Account or Card Number must be entered&hellip;"). Supplying both, or neither, is a
     *       {@link ValidationException} (HTTP&nbsp;400). A blank card string is treated as absent.</li>
     *   <li><b>Cross-reference resolution.</b> Both the account id and the card number must end up
     *       populated on the persisted record. When a <em>card number</em> is supplied the owning
     *       account is resolved through {@link CardXrefRepository#findByXrefCardNum(String)} (the
     *       legacy {@code READ CCXREF} keyed by card); when an <em>account id</em> is supplied the
     *       account's existence is confirmed and a representative card number is resolved through
     *       {@link CardXrefRepository#findByXrefAcctId(Long, org.springframework.data.domain.Pageable)}.
     *       A missing cross-reference or account is a {@link ResourceNotFoundException}
     *       (HTTP&nbsp;404).</li>
     *   <li><b>Date validation.</b> The origination and processing dates are validated through
     *       {@link DateValidationService} (the {@code CSUTLDTC} parity) and converted to
     *       start-of-day {@link LocalDateTime}s. Both are persisted (AAP &sect;0.7.3 #10).</li>
     *   <li><b>Id generation.</b> The 16-character zero-padded id is drawn from
     *       {@link TranIdGenerator} (the database-sequence replacement for the
     *       {@code STARTBR}/{@code READPREV}&nbsp;+&nbsp;1 browse), used <strong>only</strong> on
     *       this online add path.</li>
     *   <li><b>Persist &amp; return.</b> The client business attributes are copied by
     *       {@link TransactionMapper#toEntity(TransactionAddRequest)}; this service then stamps the
     *       server-owned fields ({@code tranId}, {@code acctId}, {@code cardNum}, {@code origTs},
     *       {@code procTs}), saves, and returns the full detail DTO.</li>
     * </ol>
     *
     * <p>Per-field "&hellip; can NOT be empty&hellip;" / numeric-format rules are enforced
     * declaratively by the Bean Validation constraints on {@link TransactionAddRequest} at the
     * controller boundary; this method owns the cross-field key rule and the cross-reference,
     * date, id, and persistence logic that the COBOL program performed inline.</p>
     *
     * @param request the add request carrying exactly one of {@code accountId}/{@code cardNum}
     *                plus the transaction business attributes; must not be {@code null}
     * @return the persisted transaction as a {@link TransactionResponse} detail DTO (both timestamps)
     * @throws ValidationException       if the request is {@code null}, if the Account/Card key rule
     *                                   is violated, or if a supplied date is invalid (HTTP&nbsp;400)
     * @throws ResourceNotFoundException if the supplied card has no cross-reference, or the supplied
     *                                   account does not exist or owns no card (HTTP&nbsp;404)
     */
    @Transactional
    public TransactionResponse addTransaction(TransactionAddRequest request) {
        if (request == null) {
            throw new ValidationException("Transaction request must be supplied");
        }

        // --- Step 1: Account ID XOR Card Number (COTRN02C key rule) ---------------------------
        // A blank card string is treated as "not supplied" so a controller-bypassing caller cannot
        // sneak an empty card past the XOR. accountId is a Long, so null is its only "absent" state.
        boolean hasAccount = request.accountId() != null;
        boolean hasCard = request.cardNum() != null && !request.cardNum().isBlank();
        if (hasAccount == hasCard) {
            // Both present OR both absent -> reject (mirrors COTRN02C
            // "Account or Card Number must be entered...").
            throw new ValidationException("Provide exactly one of Account ID or Card Number");
        }

        // --- Step 2: Resolve the (acctId, cardNum) pair via the card cross-reference -----------
        // Both must end up populated on the entity. Blank-final locals keep them effectively final
        // for use in the orElseThrow lambdas.
        final Long acctId;
        final String cardNum;
        if (hasCard) {
            // Card key path: READ CCXREF by card number -> owning account id.
            final String reqCard = request.cardNum();
            CardXref xref = cardXrefRepository.findByXrefCardNum(reqCard)
                    .orElseThrow(() -> ResourceNotFoundException.of("Card cross-reference", reqCard));
            acctId = xref.getXrefAcctId();
            cardNum = reqCard;
        } else {
            // Account key path: confirm the account exists, then resolve a representative card
            // number for it (the legacy program echoes XREF-CARD-NUM back onto the screen).
            final Long reqAcct = request.accountId();
            if (!accountRepository.existsById(reqAcct)) {
                throw ResourceNotFoundException.of("Account", reqAcct);
            }
            cardNum = cardXrefRepository.findByXrefAcctId(reqAcct, PageRequest.of(0, 1))
                    .getContent().stream().findFirst()
                    .map(CardXref::getXrefCardNum)
                    .orElseThrow(() -> ResourceNotFoundException.of("Card cross-reference for account", reqAcct));
            acctId = reqAcct;
        }

        // --- Step 2.5: Validate the (typeCd, categoryCd) reference exists (fk_tran_cat guard) ----
        // The relational schema adds a composite foreign key
        //   fk_tran_cat (type_cd, cat_cd) -> transaction_category (type_cd, cat_cd)   (AAP §0.3.1).
        // A syntactically valid but non-existent type/category pair would otherwise pass the
        // declarative Bean Validation, reach the INSERT, and trip the foreign key at flush -
        // surfacing as an unhandled HTTP 500 for client-correctable input. Pre-validating the
        // reference here reports a missing pair as a clean HTTP 404, mirroring the account/card
        // not-found paths above so every "referenced data does not exist" input returns a 4xx.
        TransactionCategory.TransactionCategoryId categoryId =
                new TransactionCategory.TransactionCategoryId(request.typeCd(), request.categoryCd());
        if (!transactionCategoryRepository.existsById(categoryId)) {
            throw ResourceNotFoundException.of(
                    "Transaction type/category", request.typeCd() + "/" + request.categoryCd());
        }

        // --- Step 3: Validate origination & processing dates (CSUTLDTC parity) -----------------
        // Both timestamps are persisted distinctly (discrepancy #10); a validated date becomes the
        // start-of-day instant, matching the COBOL move of the X(10) date into the X(26) timestamp.
        LocalDateTime origTs = validateToTimestamp(request.origDate(), "Orig Date");
        LocalDateTime procTs = validateToTimestamp(request.procDate(), "Proc Date");

        // --- Step 4: Build the entity (mapper copies client attributes; service owns the rest) --
        Transaction tx = transactionMapper.toEntity(request);
        tx.setTranId(tranIdGenerator.generateTransactionId()); // 16-char LPAD, online add path only
        tx.setAcctId(acctId);
        tx.setCardNum(cardNum);
        tx.setOrigTs(origTs);
        tx.setProcTs(procTs);

        // --- Step 5 & 6: Persist and return the full detail DTO --------------------------------
        Transaction saved = transactionRepository.save(tx);
        return transactionMapper.toResponse(saved);
    }

    /**
     * Validates a raw request date string through {@link DateValidationService} (the
     * {@code CSUTLDTC} parity) and converts it to a start-of-day {@link LocalDateTime} suitable for
     * the {@code orig_ts} / {@code proc_ts} timestamp columns.
     *
     * <p>The request carries {@code origDate}/{@code procDate} as the raw {@code YYYY-MM-DD}
     * <strong>text</strong> the client supplied (see {@link TransactionAddRequest}), so date
     * validation is delegated <em>entirely and exclusively</em> to {@link DateValidationService}:
     * this service never hand-rolls date parsing. The service applies the {@code CSUTLDTC} rules
     * (month {@code 01-12}, real day-of-month, leap-year correctness) and, on any malformed or
     * impossible date, raises a {@link ValidationException} whose message is
     * <em>field-specific</em> (for example "Orig Date is not a valid date; expected format
     * YYYY-MM-DD") &mdash; the global handler renders that as HTTP&nbsp;400. Accepting the raw text
     * here (rather than a pre-parsed {@link LocalDate}) is what keeps the wired date validator
     * <strong>live</strong> on the add path: a strict Jackson {@code LocalDate} deserializer would
     * otherwise reject an invalid date before this service ran, degrading the message to the generic
     * "Malformed request body." On success the canonical parsed value is anchored at
     * {@link LocalDate#atStartOfDay()}, mirroring the COBOL move of the {@code X(10)} screen date
     * into the {@code X(26)} timestamp field.</p>
     *
     * @param date       the raw request date text to validate; may be {@code null} (rejected by the
     *                   service with a field-specific message)
     * @param fieldLabel the human-readable field label used in the validation error message
     * @return the validated date as a start-of-day {@link LocalDateTime}
     * @throws ValidationException if {@code date} is {@code null}, blank, or not a valid calendar date
     */
    private LocalDateTime validateToTimestamp(String date, String fieldLabel) {
        LocalDate validated = dateValidationService.validateAndParseDate(date, fieldLabel);
        return validated.atStartOfDay();
    }
}
