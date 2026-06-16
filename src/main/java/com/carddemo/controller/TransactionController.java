package com.carddemo.controller;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionResponse;
import com.carddemo.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo <strong>transaction</strong> list / view / add operations.
 *
 * <p>This controller is the Spring Boot re-expression of three legacy CICS online programs that
 * managed transactions on the {@code TRANSACT} VSAM file from the 3270 terminal. Honoring the
 * migration's "one operation per original transaction id" rule, each source program maps to exactly
 * one HTTP endpoint:</p>
 * <ul>
 *   <li>{@code COTRN00C} &mdash; transaction {@code CT00}, the <em>List Transactions</em> browse.
 *       The legacy program walked the {@code TRANSACT.AIX} alternate index in origination-timestamp
 *       order, paging seven rows at a time (PF7/PF8) and offering an {@code 'S'} line selection to
 *       drill into a single transaction. This becomes {@code GET /transactions}.</li>
 *   <li>{@code COTRN01C} &mdash; transaction {@code CT01}, the <em>View Transaction</em> detail
 *       lookup of a single transaction by its 16-character transaction id. This becomes
 *       {@code GET /transactions/{tranId}}.</li>
 *   <li>{@code COTRN02C} &mdash; transaction {@code CT02}, the <em>Add Transaction</em> entry screen.
 *       The operator supplied <em>either</em> an account id <em>or</em> a card number (the other
 *       being derived through the card cross-reference), the transaction type / category / source /
 *       amount / description, the origination and processing dates, and the merchant
 *       id / name / city / zip; a {@code CONFIRM} (Y/N) flag then committed the write. The next
 *       transaction id was derived with a {@code STARTBR}/{@code READPREV} browse. This becomes
 *       {@code POST /transactions}.</li>
 * </ul>
 *
 * <p>The BMS screen maps {@code COTRN00} / {@code COTRN01} / {@code COTRN02} that rendered these
 * functions are <strong>retired</strong>; their field layouts informed the request/response DTOs
 * ({@link TransactionListItem}, {@link TransactionResponse}, {@link TransactionAddRequest}) rather
 * than any rendered UI. The deliverable is a stateless JSON REST contract.</p>
 *
 * <h2>Thin-controller contract</h2>
 * <p>This class holds <strong>no business logic</strong>. Every endpoint simply binds the request
 * parameters / path variables / body and delegates to {@link TransactionService}, which owns all
 * behaviour ported from the COBOL paragraphs. In particular, the following are
 * <strong>service-owned</strong> and deliberately <em>absent</em> from this controller:</p>
 * <ul>
 *   <li>the fixed legacy page size of <strong>7</strong> rows, applied service-side as
 *       {@code PageRequest.of(page, 7)} &mdash; the controller forwards only the zero-based
 *       {@code page} index and never passes a page size, so the size-7 window can never be
 *       overridden from the API surface;</li>
 *   <li>the <em>Account&nbsp;ID&nbsp;XOR&nbsp;Card&nbsp;Number</em> key rule and the card
 *       cross-reference resolution that populates the missing key;</li>
 *   <li>the 16-character transaction-id generation (the database-sequence replacement for the
 *       legacy {@code STARTBR}/{@code READPREV} scheme);</li>
 *   <li>the {@code CSUTLDTC}-equivalent origination/processing date validation and the stamping of
 *       both timestamps.</li>
 * </ul>
 *
 * <h2>Error mapping</h2>
 * <p>Error translation is centralized in the application's {@code @RestControllerAdvice}
 * ({@code GlobalExceptionHandler}); the controller adds no per-endpoint error handling:</p>
 * <ul>
 *   <li>a view of an unknown transaction id raises {@code ResourceNotFoundException} &rarr;
 *       HTTP&nbsp;404;</li>
 *   <li>an add whose supplied card / account has no cross-reference (or whose account does not
 *       exist) raises {@code ResourceNotFoundException} &rarr; HTTP&nbsp;404;</li>
 *   <li>an add that violates the Account/Card key rule, or that carries an invalid date, raises
 *       {@code ValidationException} &rarr; HTTP&nbsp;400;</li>
 *   <li>an add body that violates the {@link TransactionAddRequest} Bean Validation constraints
 *       raises {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All three endpoints are reachable by <em>any</em> authenticated caller. Authentication is
 * enforced globally by the stateless JWT filter chain ({@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} rule), so no method-level {@code @PreAuthorize} guard is
 * declared here &mdash; a missing or invalid token yields HTTP&nbsp;401. The paths are absolute
 * ({@code /transactions}, {@code /transactions/{tranId}}); there is no {@code /api} prefix.</p>
 *
 * <h2>Sensitive-data handling</h2>
 * <p>The transaction record carries a card number, which appears in {@link TransactionResponse} as
 * it did on the legacy {@code COTRN01} view screen; this controller neither suppresses nor adds any
 * cleartext logging of it. The transaction record has no card verification value (CVV), so no CVV
 * can ever be exposed through these endpoints.</p>
 *
 * @see TransactionService
 * @see TransactionListItem
 * @see TransactionResponse
 * @see TransactionAddRequest
 * @see PageResponse
 * @see <a href="file:app/cbl/COTRN00C.cbl">app/cbl/COTRN00C.cbl (List Transactions, tran CT00)</a>
 * @see <a href="file:app/cbl/COTRN01C.cbl">app/cbl/COTRN01C.cbl (View Transaction, tran CT01)</a>
 * @see <a href="file:app/cbl/COTRN02C.cbl">app/cbl/COTRN02C.cbl (Add Transaction, tran CT02)</a>
 */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    /**
     * Application service that encapsulates all transaction business logic ported from
     * {@code COTRN00C}, {@code COTRN01C} and {@code COTRN02C}. Injected through the constructor and
     * held {@code final} so the collaborator is immutable for the lifetime of this singleton bean.
     */
    private final TransactionService transactionService;

    /**
     * Creates the controller with its required {@link TransactionService} collaborator.
     *
     * <p>Constructor injection (replacing the COBOL {@code CALL} / {@code XCTL} static linkage) makes
     * the dependency explicit, mandatory and immutable, and keeps the controller trivially testable
     * with a mocked service.</p>
     *
     * @param transactionService the transaction service to delegate every request to; never
     *                           {@code null}
     */
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Lists transactions &mdash; the REST re-expression of {@code COTRN00C} (transaction
     * {@code CT00}).
     *
     * <p>The optional {@code accountId} reproduces the legacy account scoping of the browse: the
     * controller forwards the value verbatim and lets the service own the {@code null} semantics and
     * apply the fixed legacy window of seven rows per page ({@code PageRequest.of(page, 7)}). Only the
     * zero-based {@code page} index is forwarded; the controller never passes a page size, so the
     * size-7 contract cannot be overridden here. The returned {@link PageResponse} carries the page
     * metadata (totals, first/last flags) that reproduce the program's PF7/PF8 forward/backward paging
     * indicators.</p>
     *
     * @param accountId optional owning-account filter; {@code null} / absent and a concrete value are
     *                  both handled by the service's scoping rules
     * @param page      the zero-based page index to retrieve; defaults to {@code 0} (the first page)
     *                  when the parameter is absent
     * @return HTTP&nbsp;200 with a {@link PageResponse} of {@link TransactionListItem} rows whose
     *         {@code size} is the legacy 7
     */
    @GetMapping
    public ResponseEntity<PageResponse<TransactionListItem>> listTransactions(
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(transactionService.listTransactions(accountId, page));
    }

    /**
     * Views a single transaction's detail &mdash; the REST re-expression of {@code COTRN01C}
     * (transaction {@code CT01}).
     *
     * <p>Looks up the transaction by its 16-character transaction id supplied in the path and returns
     * the full detail projection, carrying <strong>both</strong> the origination and processing
     * timestamps. A transaction id that does not exist causes the service to raise
     * {@code ResourceNotFoundException}, which the global exception handler translates into
     * HTTP&nbsp;404.</p>
     *
     * @param tranId the 16-character transaction identifier to look up ({@code TRAN-ID})
     * @return HTTP&nbsp;200 with the matching {@link TransactionResponse} (both timestamps);
     *         HTTP&nbsp;404 if no transaction has the given id
     */
    @GetMapping("/{tranId}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable String tranId) {
        return ResponseEntity.ok(transactionService.getTransaction(tranId));
    }

    /**
     * Adds a new transaction &mdash; the REST re-expression of {@code COTRN02C} (transaction
     * {@code CT02}).
     *
     * <p>The request body ({@link TransactionAddRequest}) carries exactly one of an account id or a
     * card number plus the transaction business attributes; it is validated with {@code @Valid}, so a
     * field-level constraint violation raises {@code MethodArgumentNotValidException} &rarr;
     * HTTP&nbsp;400. The service owns the cross-field rules the COBOL program performed inline &mdash;
     * the <em>Account&nbsp;ID&nbsp;XOR&nbsp;Card&nbsp;Number</em> key rule (a violation raises
     * {@code ValidationException} &rarr; HTTP&nbsp;400), the card cross-reference resolution (a missing
     * cross-reference / account raises {@code ResourceNotFoundException} &rarr; HTTP&nbsp;404), the
     * {@code CSUTLDTC} date validation, the 16-character transaction-id generation, and the stamping of
     * both timestamps &mdash; none of which appear in this controller.</p>
     *
     * <p>On success the persisted transaction is returned with HTTP&nbsp;<strong>201&nbsp;Created</strong>,
     * reflecting that a new resource was created (the legacy program wrote a new {@code TRANSACT}
     * record).</p>
     *
     * @param request the validated add request; carries exactly one of {@code accountId} /
     *                {@code cardNum} plus the transaction attributes
     * @return HTTP&nbsp;201 with the created {@link TransactionResponse} (both timestamps)
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> addTransaction(
            @Valid @RequestBody TransactionAddRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.addTransaction(request));
    }
}
