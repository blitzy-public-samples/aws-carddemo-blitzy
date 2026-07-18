/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionAddRequest;
import com.aws.carddemo.dto.TransactionAddResponse;
import com.aws.carddemo.mapper.TransactionMapper;
import com.aws.carddemo.service.TransactionService;
import com.aws.carddemo.service.TransactionService.AddTransactionCommand;
import com.aws.carddemo.service.TransactionService.TransactionValidationException;

/**
 * REST controller for the CardDemo <strong>Transaction Add</strong> screen &mdash;
 * the Java re-platform of the online COBOL/CICS program {@value #PROGRAM_NAME}
 * (CICS transaction {@value #TRANSACTION_ID}, BMS map {@code COTRN2A} /
 * mapset {@code COTRN02}), relocated to {@code legacy/cbl/COTRN02C.cbl}.
 *
 * <p>The legacy program let an operator key a new transaction, echoed the values
 * back with a confirm prompt, and only wrote the record once the operator
 * confirmed &mdash; a <em>validate&nbsp;&rarr;&nbsp;confirm&nbsp;(Y/N)&nbsp;&rarr;&nbsp;add</em>
 * flow, with a "copy the last transaction" convenience on PF5. That behavior is
 * preserved here without feature expansion; the 3270 pseudo-conversational model
 * (COMMAREA&nbsp;+&nbsp;{@code RETURN TRANSID}&nbsp;+&nbsp;{@code XCTL}) becomes a
 * stateless request/response per screen submit (AAP&nbsp;&sect;0.7.1&nbsp;H1).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/v1/transactions/add} &mdash; the first-entry blank
 *       Transaction Add screen (the COBOL first-time {@code SEND} with
 *       {@code CDEMO-PGM-REENTER} not yet set).</li>
 *   <li>{@code POST /api/v1/transactions/add} &mdash; a screen submit; routed on
 *       the transmitted attention key ({@link TransactionAddRequest#action()}),
 *       reproducing the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.</li>
 * </ul>
 *
 * <h2>Attention-key routing (COBOL {@code EVALUATE EIBAID})</h2>
 * <ul>
 *   <li><strong>ENTER</strong> (also the default when no key is supplied) &mdash;
 *       {@code PROCESS-ENTER-KEY}: run the confirm-then-commit flow.</li>
 *   <li><strong>PF3</strong> &mdash; {@code RETURN-TO-PREV-SCREEN}: navigate back
 *       to the main menu ({@value #BACK_PROGRAM_NAME} / {@value #BACK_TRANSACTION_ID}).</li>
 *   <li><strong>PF4</strong> &mdash; {@code CLEAR-CURRENT-SCREEN}: redisplay a
 *       blank form.</li>
 *   <li><strong>PF5</strong> &mdash; {@code COPY-LAST-TRAN-DATA}: pre-fill from the
 *       last transaction (see the copy-last note below).</li>
 *   <li><strong>any other key</strong> &mdash; the COBOL {@code WHEN OTHER}
 *       "{@code Invalid key pressed...}" redisplay.</li>
 * </ul>
 *
 * <h2>Confirm-then-commit flow (COBOL {@code PROCESS-ENTER-KEY})</h2>
 * <p>On ENTER the confirm flag ({@link TransactionAddRequest#confirm()}) is
 * evaluated exactly as the legacy {@code EVALUATE CONFIRMI}:</p>
 * <ul>
 *   <li>{@code 'Y'}/{@code 'y'} &mdash; build the service command and add the
 *       transaction; on success redisplay with the COBOL
 *       "{@code Transaction added successfully...}" message carrying the generated id.</li>
 *   <li>{@code 'N'}/{@code 'n'}, blank, or absent &mdash; redisplay with the
 *       "{@code Confirm to add this transaction...}" prompt. (The legacy program
 *       treats {@code 'N'} identically to a blank confirm &mdash; it re-prompts;
 *       it does not cancel or clear &mdash; and that parity is preserved here.)</li>
 *   <li>any other value &mdash; the "{@code Invalid value. Valid values are (Y/N)...}"
 *       message. The one-character confirm field carries no value {@code @Pattern}, so a
 *       non-{@code Y}/{@code N} confirm reaches this branch and is echoed back on-screen
 *       (HTTP 200), reproducing the legacy {@code EVALUATE CONFIRMI WHEN OTHER} edit rather
 *       than being rejected at the transport layer.</li>
 * </ul>
 *
 * <h2>Division of responsibility</h2>
 * <p>All field edits and the transaction-id assignment live in the service layer,
 * never here: {@link TransactionService#addTransaction(AddTransactionCommand)}
 * reproduces the COBOL {@code VALIDATE-INPUT-KEY-FIELDS} /
 * {@code VALIDATE-INPUT-DATA-FIELDS} edits and assigns the 16-character id via the
 * increment-from-max generator (the reverse-browse parity of
 * {@code COTRN02C} lines 444-451). This controller only performs
 * transport concerns: attention-key routing, the confirm decision, request/response
 * mapping, and surfacing the service outcome on the screen. Structural request
 * shape is enforced by {@link Valid Jakarta Bean Validation} on the request body.</p>
 *
 * <h2>Outcome and exception handling</h2>
 * <p>A failed input edit is signalled by the service as a
 * {@link TransactionValidationException} carrying the exact legacy {@code WS-MESSAGE}
 * text. Because the legacy program surfaced such an edit failure as an ordinary
 * screen redisplay (a {@code SEND}, not an abend), it is rendered here as a
 * {@code 200 OK} same-screen response echoing the submitted values with that
 * message &mdash; the "business validation outcome becomes a same-screen message"
 * contract. Genuine keyed-lookup and integrity outcomes are <em>not</em> handled
 * here: a {@code RecordNotFoundException} (unknown account or card cross-reference)
 * and a {@code DuplicateKeyException} (id collision) propagate to the shared
 * {@code GlobalExceptionHandler}, which maps them to {@code 404} and {@code 409}
 * respectively (AAP&nbsp;&sect;0.7.2&nbsp;M1). The monetary amount is carried as a
 * {@link BigDecimal} end to end; no binary floating-point type is used.</p>
 *
 * <h2>Copy-last (PF5)</h2>
 * <p>The legacy {@code COPY-LAST-TRAN-DATA} pre-filled the entry fields from the
 * highest-keyed transaction and then fell through to {@code PROCESS-ENTER-KEY}.
 * That behavior is reproduced faithfully with clean layering: the service fetches
 * the last transaction ({@link TransactionService#findLastTransaction()}, the
 * re-platform of the {@code HIGH-VALUES}/{@code STARTBR}/{@code READPREV} reverse
 * browse), the mapper copies its reusable detail fields onto the submitted request
 * ({@link TransactionMapper#copyLastInto(TransactionAddRequest, Transaction)})
 * while preserving the operator-entered account/card key, and the request is run
 * through the same enter-key flow &mdash; so with a blank confirm the copied values
 * are shown under the "{@code Confirm to add this transaction...}" prompt, and a
 * confirmed ({@code 'Y'}) submit adds the copied transaction. When the transaction
 * master is empty there is nothing to copy, so the form is redisplayed unchanged
 * with an informational message.</p>
 */
@RestController
@RequestMapping("/api/v1/transactions/add")
public class TransactionAddController {

    /**
     * This program's identity, matching the COBOL {@code WS-PGMNAME} literal
     * {@code 'COTRN02C'}. The header of every add-screen response is rendered by
     * {@link TransactionMapper} from this identity.
     */
    private static final String PROGRAM_NAME = "COTRN02C";

    /**
     * This program's CICS transaction id, matching the COBOL {@code WS-TRANID}
     * literal {@code 'CT02'}.
     */
    private static final String TRANSACTION_ID = "CT02";

    /**
     * Back-navigation target program, matching the COBOL {@code MOVE 'COMEN01C'
     * TO CDEMO-TO-PROGRAM} default of {@code RETURN-TO-PREV-SCREEN} (the main menu).
     */
    private static final String BACK_PROGRAM_NAME = "COMEN01C";

    /**
     * Back-navigation target transaction id (the main menu, CICS {@code CM00}),
     * surfaced in the PF3 navigation response header.
     */
    private static final String BACK_TRANSACTION_ID = "CM00";

    /**
     * Response-header name carrying the next program to enter &mdash; the stateless
     * analog of the COBOL {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} navigation target.
     * Emitted uniformly across the online controllers so every screen advertises its
     * navigation the same way (the header-based navigation contract).
     */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /**
     * Response-header name carrying the next screen's CICS transaction id &mdash; the
     * companion of {@value #HEADER_NEXT_PROGRAM}, matching the transaction the target
     * program runs under.
     */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * Fixed decimal scale (two fraction digits) for monetary values, matching the
     * COBOL {@code PIC S9(8)V99} amount.
     */
    private static final int AMOUNT_SCALE = 2;

    /** Confirm flag "yes" value ({@code CONFIRMI = 'Y'}); compared case-insensitively. */
    private static final String CONFIRM_YES = "Y";

    /** Confirm flag "no" value ({@code CONFIRMI = 'N'}); compared case-insensitively. */
    private static final String CONFIRM_NO = "N";

    /**
     * The COBOL {@code 'Confirm to add this transaction...'} prompt, shown when the
     * operator submits with a blank or {@code 'N'} confirm flag.
     */
    private static final String CONFIRM_PROMPT_MESSAGE = "Confirm to add this transaction...";

    /**
     * The COBOL {@code 'Invalid value. Valid values are (Y/N)...'} message for a
     * confirm flag outside the {@code Y}/{@code N}/blank set.
     */
    private static final String INVALID_CONFIRM_MESSAGE = "Invalid value. Valid values are (Y/N)...";

    /**
     * The COBOL {@code CCDA-MSG-INVALID-KEY} text ({@code CSMSG01Y}) used by the
     * {@code WHEN OTHER} attention-key branch.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * Informational message for the PF5 copy-last branch when the transaction master
     * is empty and there is no prior transaction to copy (see the class-level
     * copy-last note). The legacy screen would simply have nothing to pre-fill.
     */
    private static final String COPY_LAST_UNAVAILABLE_MESSAGE =
            "Copy last transaction is not available. Please enter transaction details.";

    /**
     * Transaction application service &mdash; owner of the COBOL input edits, the
     * account/card cross-reference resolution, and the increment-from-max id
     * assignment. Injected via the constructor (no field injection).
     */
    private final TransactionService transactionService;

    /**
     * Hand-written entity/DTO mapper for the Transaction Add screen &mdash; renders
     * the response header and echoes entered or persisted values back to the client.
     * Injected via the constructor (no field injection).
     */
    private final TransactionMapper transactionMapper;

    /**
     * Creates the controller with its collaborators.
     *
     * @param transactionService the transaction service; must not be {@code null}
     * @param transactionMapper  the transaction mapper; must not be {@code null}
     */
    public TransactionAddController(TransactionService transactionService,
                                    TransactionMapper transactionMapper) {
        this.transactionService = transactionService;
        this.transactionMapper = transactionMapper;
    }

    /**
     * Returns the first-entry, blank Transaction Add screen &mdash; the Java analog
     * of the COBOL first-time {@code SEND-TRNADD-SCREEN} (before
     * {@code CDEMO-PGM-REENTER} is set), with the standard header populated and
     * every entry field cleared.
     *
     * @return {@code 200 OK} with a blank {@link TransactionAddResponse}
     */
    @GetMapping
    public ResponseEntity<TransactionAddResponse> addForm() {
        LocalDateTime now = LocalDateTime.now();
        return ResponseEntity.ok(transactionMapper.toAddResponse(blankRequest(), null, now));
    }

    /**
     * Handles a Transaction Add screen submit, routing on the transmitted attention
     * key to reproduce the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}. A
     * {@code null} action is treated as ENTER (the default submit/continue key).
     *
     * @param request the submitted add-screen fields and attention key; validated
     *                for structural shape by {@link Valid}
     * @return {@code 200 OK} with a {@link TransactionAddResponse} for every routed
     *         outcome; keyed-lookup and integrity failures raised by the service
     *         propagate to the global handler (404/409)
     */
    @PostMapping
    public ResponseEntity<TransactionAddResponse> add(@Valid @RequestBody TransactionAddRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PfKeyAction action = (request.action() == null) ? PfKeyAction.ENTER : request.action();
        return switch (action) {
            // DFHENTER -> PROCESS-ENTER-KEY (validate -> confirm -> add).
            case ENTER -> processEnter(request, now);
            // DFHPF3 -> RETURN-TO-PREV-SCREEN (navigate back to the main menu).
            case PF3 -> backToMenu();
            // DFHPF4 -> CLEAR-CURRENT-SCREEN (blank form redisplay).
            case PF4 -> ResponseEntity.ok(transactionMapper.toAddResponse(blankRequest(), null, now));
            // DFHPF5 -> COPY-LAST-TRAN-DATA (copy the last transaction's detail fields).
            case PF5 -> copyLastTransaction(request, now);
            // WHEN OTHER -> CCDA-MSG-INVALID-KEY redisplay.
            default -> ResponseEntity.ok(transactionMapper.toAddResponse(request, INVALID_KEY_MESSAGE, now));
        };
    }

    /**
     * Reproduces the COBOL {@code COPY-LAST-TRAN-DATA} paragraph (PF5, "Copy Last
     * Tran"). The highest-keyed transaction is fetched through the service (the
     * re-platform of the {@code MOVE HIGH-VALUES TO TRAN-ID} /
     * {@code STARTBR}&nbsp;/&nbsp;{@code READPREV}&nbsp;/&nbsp;{@code ENDBR} reverse
     * browse), its reusable detail fields are copied onto the submitted request
     * while the operator-entered account/card key is preserved, and the resulting
     * request is fed straight through
     * {@link #processEnter(TransactionAddRequest, LocalDateTime)} &mdash; the exact
     * fall-through the COBOL paragraph performed when it ended with
     * {@code PERFORM PROCESS-ENTER-KEY}. With the confirm flag still blank the
     * operator sees the copied values under the "{@code Confirm to add this
     * transaction...}" prompt; a confirmed ({@code 'Y'}) submit adds the copied
     * transaction, exactly as the legacy flow allowed.
     *
     * <p>When the transaction master is empty there is nothing to copy (the legacy
     * browse would have raised end-of-file and skipped the moves), so the entry
     * screen is redisplayed unchanged with the
     * "{@value #COPY_LAST_UNAVAILABLE_MESSAGE}" note.</p>
     *
     * @param request the submitted add-screen fields, supplying the preserved
     *                account/card key and confirm flag
     * @param now     the timestamp used to render the response header
     * @return {@code 200 OK} with the copied values run through the enter-key flow,
     *         or the unchanged screen when no prior transaction exists
     */
    private ResponseEntity<TransactionAddResponse> copyLastTransaction(TransactionAddRequest request,
                                                                       LocalDateTime now) {
        Optional<Transaction> last = transactionService.findLastTransaction();
        if (last.isEmpty()) {
            return ResponseEntity.ok(
                    transactionMapper.toAddResponse(request, COPY_LAST_UNAVAILABLE_MESSAGE, now));
        }
        // COPY-LAST-TRAN-DATA: copy the detail fields, preserve the operator key, then
        // fall through to PROCESS-ENTER-KEY exactly as the COBOL paragraph did.
        TransactionAddRequest copied = transactionMapper.copyLastInto(request, last.get());
        return processEnter(copied, now);
    }

    /**
     * Builds the PF3 back-navigation response and emits the header-based navigation
     * contract. The body (rendered by {@link #backToMenuResponse()}) still names the
     * menu target for backward compatibility, and the
     * {@value #HEADER_NEXT_PROGRAM}/{@value #HEADER_NEXT_TRANSACTION} response headers
     * advertise the same target ({@value #BACK_PROGRAM_NAME} /
     * {@value #BACK_TRANSACTION_ID}) uniformly with the other online controllers.
     *
     * @return {@code 200 OK} naming the main-menu target in both the navigation
     *         headers and the response body
     */
    private ResponseEntity<TransactionAddResponse> backToMenu() {
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, BACK_PROGRAM_NAME)
                .header(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION_ID)
                .body(backToMenuResponse());
    }

    /**
     * Reproduces the COBOL {@code PROCESS-ENTER-KEY} confirm decision (the
     * {@code EVALUATE CONFIRMI}). The field edits themselves are owned by the
     * service and run only on the confirmed-add path; here we branch on the
     * confirm flag:
     * <ul>
     *   <li>{@code 'Y'}/{@code 'y'} &rarr; confirm and add;</li>
     *   <li>{@code 'N'}/{@code 'n'}, blank, or absent &rarr; the
     *       "{@code Confirm to add this transaction...}" prompt (the legacy program
     *       treats {@code 'N'} the same as a blank confirm);</li>
     *   <li>anything else &rarr; the "{@code Invalid value. Valid values are (Y/N)...}"
     *       message (the one-character confirm carries no value {@code @Pattern}, so a
     *       non-{@code Y}/{@code N} confirm reaches this branch and is echoed back at HTTP 200).</li>
     * </ul>
     *
     * @param request the submitted add-screen fields
     * @param now     the timestamp used to render the response header
     * @return {@code 200 OK} with the appropriate same-screen response
     */
    private ResponseEntity<TransactionAddResponse> processEnter(TransactionAddRequest request,
                                                                LocalDateTime now) {
        String confirm = (request.confirm() == null) ? "" : request.confirm();
        // WHEN 'Y' / 'y' -> PERFORM ADD-TRANSACTION.
        if (CONFIRM_YES.equalsIgnoreCase(confirm)) {
            return confirmAndAdd(request, now);
        }
        // WHEN 'N' / 'n' / SPACES / LOW-VALUES -> 'Confirm to add this transaction...'.
        if (confirm.isBlank() || CONFIRM_NO.equalsIgnoreCase(confirm)) {
            return ResponseEntity.ok(transactionMapper.toAddResponse(request, CONFIRM_PROMPT_MESSAGE, now));
        }
        // WHEN OTHER -> 'Invalid value. Valid values are (Y/N)...'.
        return ResponseEntity.ok(transactionMapper.toAddResponse(request, INVALID_CONFIRM_MESSAGE, now));
    }

    /**
     * Reproduces the confirmed-add path (COBOL {@code CONFIRMI = 'Y'} &rarr;
     * {@code ADD-TRANSACTION}). The submitted values are copied field-for-field into
     * the service command &mdash; the amount is rendered into the exact signed
     * fixed-point text the service edits expect &mdash; and the transaction is added.
     * The 16-character id is assigned by the service (never here).
     *
     * <p>A {@link TransactionValidationException} means an input edit failed; the
     * legacy program surfaced that as an ordinary screen redisplay, so it is echoed
     * back at {@code 200 OK} carrying the exact legacy message. A
     * {@code RecordNotFoundException} or {@code DuplicateKeyException} is deliberately
     * not caught &mdash; it propagates to the global handler for the correct
     * {@code 404}/{@code 409} status.</p>
     *
     * @param request the submitted add-screen fields
     * @param now     the timestamp used to render the response header
     * @return {@code 200 OK} with the success redisplay, or the same-screen edit-failure
     *         redisplay
     */
    private ResponseEntity<TransactionAddResponse> confirmAndAdd(TransactionAddRequest request,
                                                                 LocalDateTime now) {
        // Straight field copy into the service-layer command; tranId is NOT set here
        // (the service assigns it via IdGenerator). Field order matches AddTransactionCommand.
        AddTransactionCommand command = new AddTransactionCommand(
                request.accountId(),                       // ACTIDINI
                request.cardNumber(),                      // CARDNINI
                request.typeCode(),                        // TTYPCDI
                request.categoryCode(),                    // TCATCDI
                request.source(),                          // TRNSRCI
                request.description(),                     // TDESCI
                formatAmountForCommand(request.amount()),  // TRNAMTI (signed fixed-point text)
                request.originDate(),                      // TORIGDTI
                request.processDate(),                     // TPROCDTI
                request.merchantId(),                      // MIDI
                request.merchantName(),                    // MNAMEI
                request.merchantCity(),                    // MCITYI
                request.merchantZip());                    // MZIPI

        Transaction added;
        try {
            added = transactionService.addTransaction(command);
        } catch (TransactionValidationException ex) {
            // COBOL VALIDATE-INPUT-*-FIELDS failure -> MOVE msg TO WS-MESSAGE + SEND-TRNADD-SCREEN
            // (a normal same-screen redisplay, not an abend). Surface the exact message at 200 OK.
            return ResponseEntity.ok(transactionMapper.toAddResponse(request, ex.getMessage(), now));
        }

        // COBOL WRITE-TRANSACT-FILE success STRING:
        //   'Transaction added successfully. ' ' Your Tran ID is ' TRAN-ID '.'
        String successMessage =
                "Transaction added successfully. " + " Your Tran ID is " + added.getTranId() + ".";
        return ResponseEntity.ok(
                transactionMapper.toAddResponse(added, request.accountId(), successMessage, now));
    }

    /**
     * Renders a {@link BigDecimal} amount into the signed, zero-padded fixed-point
     * text the service edits expect ({@code [+-]NNNNNNNN.NN}: a leading sign, eight
     * integer digits, a decimal point, and two fraction digits), the exact shape of
     * the COBOL {@code TRNAMTI PIC X(12)} screen field. This is a transport bridge
     * from the typed DTO amount to the service's raw-text command component; it is
     * not a business edit (the numeric/format edits remain in the service).
     *
     * <p>A {@code null} amount maps to {@code null} so the service reproduces the
     * COBOL "{@code Amount can NOT be empty...}" edit. The value is normalized to
     * scale {@value #AMOUNT_SCALE} with {@link RoundingMode#HALF_UP}; the request-body
     * {@code @Digits(integer = 8, fraction = 2)} constraint keeps the integer part
     * within the eight-digit field.</p>
     *
     * @param amount the typed amount from the request; may be {@code null}
     * @return the {@code [+-]NNNNNNNN.NN} text, or {@code null} when {@code amount} is {@code null}
     */
    private static String formatAmountForCommand(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal scaled = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        String sign = (scaled.signum() < 0) ? "-" : "+";
        long totalCents = scaled.abs().movePointRight(AMOUNT_SCALE).longValueExact();
        long integerPart = totalCents / 100L;
        long fractionPart = totalCents % 100L;
        return String.format(Locale.ROOT, "%s%08d.%02d", sign, integerPart, fractionPart);
    }

    /**
     * Builds an all-empty add-screen request, used to render a blank form for the
     * first-entry ({@code GET}) and clear ({@code PF4}) screens. Every field is
     * {@code null}; the mapper still populates the standard header.
     *
     * @return an empty {@link TransactionAddRequest}
     */
    private static TransactionAddRequest blankRequest() {
        return new TransactionAddRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Builds the PF3 back-navigation response <em>body</em> &mdash; the stateless
     * analog of the COBOL {@code RETURN-TO-PREV-SCREEN} {@code XCTL} to the main
     * menu. The navigation target ({@value #BACK_PROGRAM_NAME} /
     * {@value #BACK_TRANSACTION_ID}) is named in the response body's header fields;
     * the {@value #HEADER_NEXT_PROGRAM}/{@value #HEADER_NEXT_TRANSACTION} HTTP
     * response headers that advertise the same target are added by
     * {@link #backToMenu()}. No message is set (the legacy return path sets none),
     * and the entry fields are left blank.
     *
     * @return a {@link TransactionAddResponse} naming the menu target in its body
     */
    private static TransactionAddResponse backToMenuResponse() {
        return new TransactionAddResponse(
                BACK_TRANSACTION_ID, // transactionName -> CM00 (menu target)
                null,                // title01
                null,                // currentDate
                BACK_PROGRAM_NAME,   // programName -> COMEN01C (menu target)
                null,                // title02
                null,                // currentTime
                null,                // accountId
                null,                // cardNumber
                null,                // typeCode
                null,                // categoryCode
                null,                // source
                null,                // description
                null,                // amount
                null,                // originDate
                null,                // processDate
                null,                // merchantId
                null,                // merchantName
                null,                // merchantCity
                null,                // merchantZip
                null,                // confirm
                null);               // errorMessage
    }
}
