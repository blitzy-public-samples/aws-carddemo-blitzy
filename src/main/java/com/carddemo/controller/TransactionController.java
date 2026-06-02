package com.carddemo.controller;

import com.carddemo.dto.transaction.TransactionDto;
import com.carddemo.dto.transaction.TransactionListResponse;
import com.carddemo.dto.transaction.TransactionRequest;
import com.carddemo.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Transaction list, view, and create REST endpoints &mdash; the stateless replacement for the
 * three legacy CICS online transaction programs:
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl} (transaction list, TRANID {@code CT00}) &mdash; browses the
 *       {@code TRANSACT} master ten rows at a time with {@code STARTBR}/{@code READNEXT} and
 *       PF7/PF8 navigation, optionally narrowed by account id or card number;</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (transaction view, TRANID {@code CT01}) &mdash; a
 *       transaction-id keyed read of {@code TRANSACT}
 *       ({@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(tran-id)});</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} (transaction add, TRANID {@code CT02}) &mdash; validates a new
 *       transaction and {@code WRITE}s it to {@code TRANSACT}, generating the 16-character
 *       {@code TRAN-ID}.</li>
 * </ul>
 *
 * <p>The COBOL record layout is the 350-byte {@code TRAN-RECORD} from {@code app/cpy/CVTRA05Y.cpy}.
 * The {@code TRANSACT.AIX} alternate index on {@code TRAN-ORIG-TS} is replaced by the JPA
 * {@code @Index(name="idx_transaction_orig_ts", columnList="orig_timestamp")} on the
 * {@code Transaction} entity (AAP &sect;0.6.2).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/transactions} &mdash; list transactions, paginated, with optional
 *       {@code accountId} (11-digit) and {@code cardNumber} (16-digit) filters; {@code 200 OK} with
 *       a {@link TransactionListResponse} (replaces the COTRN00C browse; PF7/PF8 paging becomes the
 *       Spring {@link Pageable} {@code page}/{@code size}/{@code sort} query parameters per AAP
 *       &sect;0.6.1).</li>
 *   <li>{@code GET /api/transactions/{tranId}} &mdash; retrieve one transaction by its 16-character
 *       id; {@code 200 OK} with a {@link TransactionDto} (replaces COTRN01C).</li>
 *   <li>{@code POST /api/transactions} &mdash; create a transaction; {@code 201 Created} with a
 *       {@code Location} header pointing at the new resource and the persisted {@link TransactionDto}
 *       in the body (replaces COTRN02C). The full validation chain (codes 100/101/102/103) and the
 *       PR-10 id generation run inside {@link TransactionService}.</li>
 * </ul>
 *
 * <h2>Thin HTTP boundary (business logic lives in {@link TransactionService})</h2>
 * <p>This controller is intentionally a thin, stateless HTTP adapter. It performs only the
 * argument-binding edits expressible as Jakarta Bean Validation annotations (the per-parameter
 * {@link Min}/{@link Pattern}/{@link Size} filters and the {@code @Valid} body check); all
 * transaction business semantics live in {@link TransactionService}, including:</p>
 * <ul>
 *   <li>the COTRN00C browse with its optional account/card narrowing &mdash;
 *       {@link TransactionService#listTransactions(Long, String, Pageable)} resolves the
 *       {@code cardNumber} filter directly, the {@code accountId} filter through the
 *       {@code CARDXREF} cross-reference, and otherwise pages the full master;</li>
 *   <li>the COTRN01C transaction-id input edits and keyed read &mdash;
 *       {@link TransactionService#getTransaction(String)} rejects a null / non-16-character id with
 *       {@code IllegalArgumentException} ({@code "Tran ID must be 16 characters"}) and a
 *       not-found id with {@code IllegalArgumentException} ({@code "Transaction ID NOT found..."}),
 *       both mapped to {@code 400} (this preserves the COTRN01C behavior of treating a bad/missing
 *       id as a re-enterable input error rather than a REST {@code 404});</li>
 *   <li>the COTRN02C add chain &mdash; {@link TransactionService#addTransaction(TransactionRequest)}
 *       performs the cross-reference / account lookups and the validation codes
 *       100 ("INVALID CARD NUMBER FOUND"), 101 (account not found), 102 ("OVERLIMIT TRANSACTION"),
 *       and 103 ("TRANSACTION RECEIVED AFTER ACCT EXPIRATION"), generates the PR-10 id, and persists
 *       the {@code Transaction} inside a {@code @Transactional} unit of work (PR-24).</li>
 * </ul>
 * <p>The exact COBOL messages are surfaced to clients by
 * {@code com.carddemo.controller.advice.GlobalExceptionHandler}, which maps
 * {@code IllegalArgumentException} / {@code InvalidCardException} &rarr; {@code 400},
 * {@code AccountNotFoundException} &rarr; {@code 404},
 * {@code OverlimitException} / {@code ExpiredAccountException} &rarr; {@code 422},
 * {@code OptimisticLockException} &rarr; {@code 409}, and bean-validation failures (the
 * {@code @Valid} body via {@code MethodArgumentNotValidException}, and the per-parameter
 * {@code @Min}/{@code @Pattern}/{@code @Size} via {@code ConstraintViolationException}) &rarr;
 * {@code 400}.</p>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any
 * <em>authenticated</em> caller (USER or ADMIN) may list, view, and create transactions, exactly as
 * the legacy transaction screens were reachable from the regular user menu ({@code COMEN01C}). The
 * requirement that the caller be authenticated is enforced by the application
 * {@code SecurityFilterChain} ({@code com.carddemo.security.SecurityConfig}); anonymous requests are
 * rejected with {@code 401} before reaching these methods (AAP &sect;0.7.2 &mdash; no feature
 * additions).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-03</b> &mdash; the four validation codes 100/101/102/103 raised by
 *       {@link TransactionService} are mapped to their HTTP statuses by
 *       {@code GlobalExceptionHandler} and documented on {@link #addTransaction} via
 *       {@code @ApiResponses}.</li>
 *   <li><b>PR-10</b> &mdash; the 16-character {@code tranId} (parmDate(10) + 6-digit suffix) is
 *       generated inside {@link TransactionService} via {@code TransactionIdGenerator}; the
 *       controller only echoes it back and builds the {@code Location} header from it.</li>
 *   <li><b>PR-11</b> &mdash; {@code origTimestamp}/{@code procTimestamp} are carried verbatim as
 *       26-character DB2 format strings on {@link TransactionDto}.</li>
 *   <li><b>PR-16</b> &mdash; the transaction amount is {@link java.math.BigDecimal}; the controller
 *       forwards {@link TransactionDto}/{@link TransactionRequest} verbatim.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}); no
 *       {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok {@link RequiredArgsConstructor}
 *       over the {@code final} {@link TransactionService} field; no {@code @Autowired} field
 *       injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Stateless paging:</b> the COTRN00C PF7/PF8 browse cursor is replaced by a Spring
 *       {@link Pageable}; the {@link PageableDefault} of {@code size=10, sort="tranId" desc} matches
 *       the COTRN00C ten-row screen and its id ordering (most recent first). Clients override via
 *       the {@code page}, {@code size}, and {@code sort} query parameters.</li>
 *   <li><b>PCI hygiene:</b> log statements never emit a full PAN; the card number is masked to its
 *       last four digits via {@link #maskCardNumber(String)} before it can reach any appender.</li>
 *   <li><b>201 semantics:</b> {@code POST} returns {@code 201 Created} with a {@code Location}
 *       header of {@code /api/transactions/{tranId}}, the canonical REST convention for resource
 *       creation, and includes the persisted {@link TransactionDto} in the body so clients need not
 *       issue a follow-up {@code GET}.</li>
 *   <li><b>Append-only:</b> there is intentionally no {@code PUT}/{@code DELETE} &mdash; the legacy
 *       system had no transaction update/delete; reversals are new transactions of opposite sign.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (CVTRA05Y transaction record layout).
 *
 * @see TransactionService the service that performs the COTRN00C/COTRN01C/COTRN02C logic
 * @see TransactionDto the transaction view payload and list element type
 * @see TransactionListResponse the paginated transaction-list payload
 * @see TransactionRequest the create request payload
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Transaction", description = "Transaction list, view, and create endpoints (replaces COTRN00C, COTRN01C, COTRN02C)")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    /**
     * Transaction business service replacing the COTRN00C (list), COTRN01C (view), and COTRN02C
     * (add) online programs. Injected by type through the Lombok-generated constructor (PR-29).
     * Each method returns a DTO ({@link TransactionDto} / {@link TransactionListResponse}), so this
     * controller never accesses the JPA entity directly.
     */
    private final TransactionService transactionService;

    /**
     * Lists transactions, one page at a time, optionally narrowed by account id or card number, and
     * returns a {@link TransactionListResponse}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COTRN00C.cbl} (TRANID {@code CT00}), which
     * browsed the {@code TRANSACT} master ten rows at a time. The (optionally filtered) paged read
     * is delegated to {@link TransactionService#listTransactions(Long, String, Pageable)}:</p>
     * <ul>
     *   <li>when {@code cardNumber} is supplied the listing is restricted to that card;</li>
     *   <li>else when {@code accountId} is supplied it is restricted to the account's cards
     *       (resolved through the {@code CARDXREF} cross-reference);</li>
     *   <li>else the full master is paged.</li>
     * </ul>
     *
     * <p>The COTRN00C PF7/PF8 paging is replaced by a stateless Spring {@link Pageable}; the cursor
     * is recomputed per request (AAP &sect;0.6.1). The {@link PageableDefault} of {@code size=10,
     * sort="tranId" desc} matches the COTRN00C ten-row screen and its id ordering (most recent
     * first). Clients may override with the {@code page}, {@code size}, and {@code sort} query
     * parameters.</p>
     *
     * <p>The per-parameter constraints are enforced during argument binding by the class-level
     * {@link Validated}; a violation raises {@code ConstraintViolationException}, mapped to
     * {@code 400} by {@code GlobalExceptionHandler}.</p>
     *
     * @param accountId  optional 11-digit account-id filter; when present must be {@code >= 1}
     *                   ({@link Min}). Ignored when {@code cardNumber} is supplied.
     * @param cardNumber optional 16-digit card-number filter; when present must match
     *                   {@code \d{16}} ({@link Pattern}). Takes precedence over {@code accountId}.
     * @param pageable   the page coordinates (defaults: {@code page=0}, {@code size=10},
     *                   {@code sort=tranId} descending)
     * @return {@code 200 OK} with the page of transactions as a {@link TransactionListResponse}
     */
    @GetMapping
    @Operation(
            summary = "List transactions (paginated)",
            description = "Lists transactions, paginated, with optional accountId (11-digit) and "
                    + "cardNumber (16-digit) filters. Replaces the COTRN00C TRANSACT browse "
                    + "(TRANID=CT00); PF7/PF8 paging becomes the page/size/sort query parameters. "
                    + "Default page size 10 matches the COTRN00C screen; sorted by transaction ID "
                    + "descending (most recent first).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transactions listed (possibly empty page)"),
            @ApiResponse(responseCode = "400", description = "Invalid filter parameters (accountId or cardNumber)"),
            @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<TransactionListResponse> listTransactions(
            @Parameter(description = "Optional 11-digit account ID filter", example = "00000000001")
            @RequestParam(value = "accountId", required = false)
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            Long accountId,
            @Parameter(description = "Optional 16-digit card number filter", example = "4111111111111111")
            @RequestParam(value = "cardNumber", required = false)
            @Pattern(regexp = "\\d{16}", message = "Card number if supplied must be a 16 digit number")
            String cardNumber,
            @Parameter(description = "Pagination parameters (page=0-indexed, size=10 default, sort=tranId desc default)")
            @PageableDefault(size = 10, sort = "tranId", direction = Sort.Direction.DESC) Pageable pageable) {

        log.debug("GET /api/transactions accountId={} cardNumber={} page={} size={} sort={}",
                accountId, maskCardNumber(cardNumber),
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());

        // Delegate to the service, which performs the (optionally filtered) paged TRANSACT browse
        // and returns a TransactionListResponse. The controller forwards it unchanged.
        TransactionListResponse response = transactionService.listTransactions(accountId, cardNumber, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a single transaction by its 16-character id and returns the {@link TransactionDto}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COTRN01C.cbl} (TRANID {@code CT01}). The
     * path variable carries the {@link Size}{@code (min=1, max=16)} edit so an empty id is rejected
     * during binding with the exact COTRN01C message {@code "Tran ID can NOT be empty..."}
     * ({@code ConstraintViolationException} &rarr; {@code 400}). The keyed {@code TRANSACT} read and
     * the remaining id edits are delegated to {@link TransactionService#getTransaction(String)},
     * which preserves the COTRN01C behavior:</p>
     * <ul>
     *   <li>not exactly 16 characters &rarr; {@code IllegalArgumentException}
     *       ({@code "Tran ID must be 16 characters"}), mapped to {@code 400};</li>
     *   <li>id not found &rarr; {@code IllegalArgumentException} ({@code "Transaction ID NOT
     *       found..."}), mapped to {@code 400} &mdash; the COTRN01C screen treats a missing id as a
     *       re-enterable input error, not a hard not-found, so this is intentionally {@code 400}
     *       rather than {@code 404}.</li>
     * </ul>
     *
     * @param tranId the 16-character transaction id ({@code TRAN-ID PIC X(16)})
     * @return {@code 200 OK} with the transaction as a {@link TransactionDto}
     */
    @GetMapping("/{tranId}")
    @Operation(
            summary = "Get a transaction by ID",
            description = "Retrieves a single transaction by its 16-character ID. "
                    + "Replaces COTRN01C CICS transaction view (TRANID=CT01). "
                    + "An empty ID returns 400 ('Tran ID can NOT be empty...'); a missing or "
                    + "malformed ID also returns 400 (re-enterable input error), per COTRN01C.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "400", description = "Tran ID can NOT be empty / not 16 characters / not found"),
            @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<TransactionDto> getTransaction(
            @Parameter(description = "16-character transaction ID", example = "2024011500000001")
            @PathVariable("tranId")
            @Size(min = 1, max = 16, message = "Tran ID can NOT be empty...")
            String tranId) {

        log.debug("GET /api/transactions/{}", tranId);

        // Delegate to the service, which validates the id format and performs the keyed read,
        // throwing IllegalArgumentException (-> 400) for a bad/missing id, and returns a
        // TransactionDto. The controller forwards it unchanged.
        TransactionDto transaction = transactionService.getTransaction(tranId);
        return ResponseEntity.ok(transaction);
    }

    /**
     * Creates a new transaction and returns {@code 201 Created} with the persisted
     * {@link TransactionDto} and a {@code Location} header.
     *
     * <p>This is the REST replacement for {@code app/cbl/COTRN02C.cbl} (TRANID {@code CT02}). The
     * full validation chain and the {@code WRITE} are delegated to
     * {@link TransactionService#addTransaction(TransactionRequest)}, which preserves the COTRN02C /
     * CBTRN02C semantics exactly:</p>
     * <ul>
     *   <li>card number not found in the cross-reference &rarr; {@code InvalidCardException} code
     *       100 ("INVALID CARD NUMBER FOUND"), mapped to {@code 400};</li>
     *   <li>account not found &rarr; {@code AccountNotFoundException} code 101, mapped to
     *       {@code 404};</li>
     *   <li>over the credit limit &rarr; {@code OverlimitException} code 102 ("OVERLIMIT
     *       TRANSACTION"), mapped to {@code 422};</li>
     *   <li>transaction after account expiration &rarr; {@code ExpiredAccountException} code 103
     *       ("TRANSACTION RECEIVED AFTER ACCT EXPIRATION"), mapped to {@code 422}.</li>
     * </ul>
     *
     * <p>{@code @Valid} triggers Jakarta Bean Validation on the {@link TransactionRequest} body
     * before the service is invoked; a body-level violation raises
     * {@code MethodArgumentNotValidException}, mapped to {@code 400} by
     * {@code GlobalExceptionHandler}. A concurrent-update conflict surfaces as
     * {@code OptimisticLockException} &rarr; {@code 409} (PR-22). The 16-character {@code tranId} is
     * generated by the service (PR-10); the {@code Location} header is built from the returned id.</p>
     *
     * @param request the new-transaction payload (account id / card number, type, category, amount,
     *                 merchant, description)
     * @return {@code 201 Created} with the persisted {@link TransactionDto} in the body and a
     *         {@code Location} header of {@code /api/transactions/{tranId}}
     */
    @PostMapping
    @Operation(
            summary = "Create a transaction",
            description = "Creates a transaction after running the COTRN02C/CBTRN02C validation chain "
                    + "(codes 100/101/102/103) and generating the 16-character transaction ID (PR-10). "
                    + "Replaces COTRN02C CICS transaction add (TRANID=CT02).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction created; Location header points to /api/transactions/{tranId}"),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid card number (code 100)"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Account not found (code 101)"),
            @ApiResponse(responseCode = "422", description = "Overlimit (code 102) or expired account (code 103)"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict")
    })
    public ResponseEntity<TransactionDto> addTransaction(
            @Valid @RequestBody TransactionRequest request) {

        // CWE-532: log only non-sensitive operational identifiers — the account surrogate id and the
        // masked card number (last four digits only). The transaction amount is sensitive financial
        // data and is never written to the application log.
        log.info("POST /api/transactions accountId={} cardNumber={}",
                request.getAccountId(), maskCardNumber(request.getCardNumber()));

        // Delegate to the service, which runs the validation chain (codes 100/101/102/103),
        // generates the 16-char tranId (PR-10), and persists the Transaction inside a
        // @Transactional unit of work (PR-24), returning the persisted TransactionDto.
        TransactionDto created = transactionService.addTransaction(request);

        // Build the canonical Location header /api/transactions/{tranId} from the generated id
        // (PR-10). java.net.URI is the whitelisted dependency for this purpose.
        URI location = URI.create("/api/transactions/" + created.getTranId());

        log.info("POST /api/transactions created transaction {}", created.getTranId());
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Masks a card number for safe logging, exposing only its last four digits (PCI hygiene). The
     * controller does this locally (rather than importing a shared masker) because its dependency
     * whitelist is limited to {@link TransactionService} and the transaction DTOs.
     *
     * @param cardNum the raw card number, or {@code null}
     * @return {@code null} if the input is {@code null}; {@code "****"} if shorter than four
     *         characters; otherwise twelve asterisks followed by the last four characters
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null) {
            return null;
        }
        if (cardNum.length() < 4) {
            return "****";
        }
        return "************" + cardNum.substring(cardNum.length() - 4);
    }
}
