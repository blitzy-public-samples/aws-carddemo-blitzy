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

import java.time.LocalDateTime;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.dto.BillPaymentRequest;
import com.aws.carddemo.dto.BillPaymentResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.BillPaymentMapper;
import com.aws.carddemo.service.BillPaymentService;
import com.aws.carddemo.service.BillPaymentService.BillPaymentResult;

/**
 * REST controller for the CardDemo <em>Bill Payment</em> screen &mdash; the Java
 * re-platform of the COBOL online program {@value #PROGRAM_NAME} (CICS
 * transaction {@value #TRANSACTION_ID}, source {@code legacy/cbl/COBIL00C.cbl}).
 * The operator enters an account id, the screen shows the current balance, and,
 * on confirmation ({@code Y}), the full balance is paid: a single bill-payment
 * transaction is posted and the account balance is reduced to zero.
 *
 * <h2>Pseudo-conversational translation (CICS &rarr; HTTP)</h2>
 * The legacy program is pseudo-conversational: {@code MAIN-PARA} (L99-L149)
 * either renders a fresh screen (first entry) or, on re-entry, receives the map
 * and dispatches on the terminal Attention Identifier ({@code EVALUATE EIBAID}),
 * finally issuing {@code EXEC CICS RETURN TRANSID('CB00')}. There is no 3270
 * terminal here, so that flow is re-expressed as two stateless HTTP endpoints:
 * <ul>
 *   <li>{@link #billPaymentScreen()} ({@code GET}) reproduces the first-entry
 *       path ({@code NOT CDEMO-PGM-REENTER}, L112-L122): a blank screen that asks
 *       for an account id.</li>
 *   <li>{@link #payBill(BillPaymentRequest)} ({@code POST}) reproduces the
 *       re-entry path (L123-L143): it routes on the submitted
 *       {@link PfKeyAction} exactly as the COBOL {@code EVALUATE EIBAID} routes on
 *       {@code DFHENTER} / {@code DFHPF3} / {@code DFHPF4} / {@code OTHER}.</li>
 * </ul>
 *
 * <h2>Separation of concerns</h2>
 * This controller performs <strong>no business logic and no balance
 * arithmetic</strong>. The entire confirmation matrix of {@code PROCESS-ENTER-KEY}
 * (L154-L244) &mdash; blank account id, the {@code Y}/{@code N}/blank/other
 * confirmation branches, the &quot;nothing to pay&quot; guard, transaction-id
 * generation, and the balance reduction &mdash; is owned by
 * {@link BillPaymentService#payBill(String, String)} inside its transactional
 * boundary. The controller only dispatches on the PF key, delegates the Enter
 * case to the service, and projects the outcome onto the response DTO through
 * {@link BillPaymentMapper}. Every monetary value stays a
 * {@link java.math.BigDecimal} at scale 2 (never {@code double}/{@code float});
 * the {@link BillPaymentResponse} canonical constructor applies that scale.
 *
 * <h2>Caller-visible outcomes</h2>
 * Business outcomes (blank id, &quot;nothing to pay&quot;, the confirm prompt,
 * and a successful payment) are carried in the {@link BillPaymentResult} returned
 * by the service and surfaced as same-screen messages with HTTP {@code 200 OK},
 * mirroring the legacy re-display of the map. A genuinely missing record (the
 * CICS {@code DFHRESP(NOTFND)} paths for the account or its card cross-reference)
 * is signalled by the service as a
 * {@link com.aws.carddemo.exception.RecordNotFoundException}, which the
 * {@code GlobalExceptionHandler} maps to HTTP {@code 404 Not Found}. That
 * exception is therefore allowed to propagate; this controller contains no
 * {@code try}/{@code catch}.
 *
 * <h2>Security and observability discipline</h2>
 * Neither the request nor the response is ever logged (AAP &sect;0.7.3 L1): the
 * account id and confirmation flag are ordinary screen inputs and carry no
 * secret, but the project-wide rule is that request/response payloads are not
 * logged from the web layer. Collaborators are supplied by constructor injection
 * only (no field injection, no {@code @Autowired}, no Lombok), so the instance is
 * fully initialized and immutable once constructed and is safe to share across
 * request threads.
 *
 * @see BillPaymentService
 * @see BillPaymentMapper
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 */
@RestController
@RequestMapping("/api/v1/billpay")
public class BillPaymentController {

    /**
     * Identifier of the legacy program this controller re-platforms
     * ({@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} in {@code COBIL00C}). Kept
     * for traceability; the screen header value itself is owned by
     * {@link BillPaymentMapper#PROGRAM_NAME}.
     */
    private static final String PROGRAM_NAME = "COBIL00C";

    /**
     * Identifier of the CICS transaction this controller re-platforms
     * ({@code WS-TRANID PIC X(04) VALUE 'CB00'} in {@code COBIL00C}). Kept for
     * traceability; the screen header value itself is owned by
     * {@link BillPaymentMapper#TRANSACTION_NAME}.
     */
    private static final String TRANSACTION_ID = "CB00";

    /**
     * Program navigated to when the operator presses PF3 (back). The COBOL
     * {@code MAIN-PARA} sets {@code CDEMO-TO-PROGRAM} to {@code 'COMEN01C'} (the
     * Main Menu) when there is no originating program (L129-L134), then performs
     * {@code RETURN-TO-PREV-SCREEN} (L273-L284, {@code EXEC CICS XCTL}).
     */
    private static final String BACK_PROGRAM_NAME = "COMEN01C";

    /**
     * Transaction id of the Main Menu screen navigated to on PF3
     * (transaction {@code CM00} drives {@code COMEN01C}). Surfaced in the response
     * header so the client knows which screen to render next.
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
     * Message shown when the operator presses a key the screen does not handle.
     * This is the shared literal {@code CCDA-MSG-INVALID-KEY} from
     * {@code legacy/cpy/CSMSG01Y.cpy} ({@code 'Invalid key pressed. Please see
     * below...'}), moved to {@code WS-MESSAGE} in the COBOL {@code EVALUATE EIBAID}
     * {@code WHEN OTHER} branch (L138-L141). The COBOL literal is a fixed
     * {@code PIC X(50)} field; the trailing padding is dropped here to match the
     * trimmed-literal convention used by the service's message constants.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * Bill-payment business logic (the Java re-platform of {@code COBIL00C}'s
     * {@code PROCESS-ENTER-KEY}). Owns the entire confirmation matrix and the
     * transactional post/update.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Hand-written mapper that assembles the {@link BillPaymentResponse} screen
     * contract (header, echoed account id, balance, message) from the outcome of a
     * request.
     */
    private final BillPaymentMapper billPaymentMapper;

    /**
     * Creates the controller with its required collaborators.
     *
     * <p>Uses constructor injection so both collaborators are {@code final} and
     * the instance is immutable once constructed. The constructor performs no work
     * beyond field assignment, so no partially-constructed instance escapes.</p>
     *
     * @param billPaymentService the bill-payment business service; must not be {@code null}
     * @param billPaymentMapper  the entity/outcome-to-DTO mapper; must not be {@code null}
     */
    public BillPaymentController(BillPaymentService billPaymentService,
                                 BillPaymentMapper billPaymentMapper) {
        this.billPaymentService = billPaymentService;
        this.billPaymentMapper = billPaymentMapper;
    }

    /**
     * Renders the initial, blank Bill Payment screen &mdash; the Java equivalent
     * of the COBOL first-entry path ({@code NOT CDEMO-PGM-REENTER}, {@code COBIL00C}
     * L112-L122), which clears the map, positions the cursor on the account-id
     * field, and sends the screen with no balance and no message.
     *
     * <p>No account has been entered yet, so the response carries only the
     * standard header (transaction / titles / date / program / time) with a
     * {@code null} account id, {@code null} balance, and {@code null} message.</p>
     *
     * @return HTTP {@code 200 OK} with a blank {@link BillPaymentResponse}
     */
    @GetMapping
    public ResponseEntity<BillPaymentResponse> billPaymentScreen() {
        return ResponseEntity.ok(blankScreen(LocalDateTime.now()));
    }

    /**
     * Handles a Bill Payment screen submission, reproducing the COBOL re-entry
     * dispatch ({@code COBIL00C} {@code MAIN-PARA} L123-L143, {@code EVALUATE
     * EIBAID}).
     *
     * <p>The submitted {@link BillPaymentRequest#action()} selects the branch,
     * exactly as the legacy {@code EVALUATE EIBAID} selected on the terminal
     * Attention Identifier:</p>
     * <ul>
     *   <li><strong>Enter</strong> ({@link PfKeyAction#ENTER}, or a {@code null}
     *       action which the request contract defaults to Enter) &rarr; delegate to
     *       {@link BillPaymentService#payBill(String, String)} and project the
     *       {@link BillPaymentResult} onto the response
     *       ({@link #toEnterResponse(String, BillPaymentResult, LocalDateTime)}).
     *       A missing account or cross-reference propagates as
     *       {@link com.aws.carddemo.exception.RecordNotFoundException} &rarr;
     *       {@code 404}.</li>
     *   <li><strong>PF3</strong> ({@link PfKeyAction#PF3}) &rarr; back navigation to
     *       the Main Menu ({@value #BACK_PROGRAM_NAME} / {@value #BACK_TRANSACTION_ID}),
     *       via {@link #toBackNavigationResponse(LocalDateTime)}.</li>
     *   <li><strong>PF4</strong> ({@link PfKeyAction#PF4}) &rarr; clear the screen
     *       ({@code CLEAR-CURRENT-SCREEN} / {@code INITIALIZE-ALL-FIELDS},
     *       L552-L566): a blank form identical to first entry.</li>
     *   <li><strong>Any other key</strong> &rarr; the {@link #INVALID_KEY_MESSAGE}
     *       on an otherwise unchanged screen (the COBOL {@code WHEN OTHER} branch,
     *       L138-L141).</li>
     * </ul>
     *
     * <p>Every branch returns HTTP {@code 200 OK}, mirroring the legacy program's
     * re-display of the map; business outcomes travel in the response body, not in
     * the HTTP status. The request body is validated with {@link Valid}; a binding
     * failure is translated to {@code 400 Bad Request} by the
     * {@code GlobalExceptionHandler}.</p>
     *
     * @param request the submitted screen data (account id, confirmation flag, and
     *                pressed key); validated on entry
     * @return HTTP {@code 200 OK} with the next {@link BillPaymentResponse} to render
     */
    @PostMapping
    public ResponseEntity<BillPaymentResponse> payBill(@Valid @RequestBody BillPaymentRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PfKeyAction action = request.action();

        // COBOL EVALUATE EIBAID (MAIN-PARA L125-L142). The request contract treats
        // a null action as the default Enter behavior.
        if (action == null || action == PfKeyAction.ENTER) {
            // WHEN DFHENTER -> PERFORM PROCESS-ENTER-KEY. The service owns the whole
            // confirm matrix; the controller only projects the outcome.
            BillPaymentResult result =
                    billPaymentService.payBill(request.accountId(), request.confirm());
            return ResponseEntity.ok(toEnterResponse(request.accountId(), result, now));
        }
        if (action == PfKeyAction.PF3) {
            // WHEN DFHPF3 -> RETURN-TO-PREV-SCREEN (XCTL to COMEN01C). The navigation
            // target is advertised through the X-CardDemo-Next-* response headers
            // (uniform with the other online controllers) as well as in the body.
            return ResponseEntity.ok()
                    .header(HEADER_NEXT_PROGRAM, BACK_PROGRAM_NAME)
                    .header(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION_ID)
                    .body(toBackNavigationResponse(now));
        }
        if (action == PfKeyAction.PF4) {
            // WHEN DFHPF4 -> CLEAR-CURRENT-SCREEN (blank all fields, re-send).
            return ResponseEntity.ok(blankScreen(now));
        }
        // WHEN OTHER -> invalid-key message on the redisplayed screen.
        return ResponseEntity.ok(billPaymentMapper.toResponse((String) null, INVALID_KEY_MESSAGE, now));
    }

    /**
     * Projects the outcome of an Enter submission onto the response contract,
     * reproducing the COBOL sequence that moves {@code ACCT-CURR-BAL} to the
     * display field {@code CURBALI} and sets {@code WS-MESSAGE} before
     * {@code SEND-BILLPAY-SCREEN}.
     *
     * <p>{@link BillPaymentMapper}'s account-id overload populates the header, the
     * echoed account id, and the message, but it cannot carry a balance (it has no
     * balance parameter). The current balance the service computed is therefore
     * layered on by re-projecting the mapper's response with
     * {@link BillPaymentResult#newBalance()} in the {@code currentBalance} slot.
     * That value is the reduced balance for a posted payment, the current balance
     * for the &quot;nothing to pay&quot; and confirm-prompt cases, or {@code null}
     * when the account was never read (blank id, invalid confirm, declined
     * {@code N}); the {@link BillPaymentResponse} canonical constructor normalizes
     * a non-{@code null} value to scale 2.</p>
     *
     * @param accountId the operator-entered account id to echo ({@code ACTIDINO})
     * @param result    the service outcome; never {@code null}
     * @param now       the timestamp used to render the header date and time
     * @return the fully-populated response for the Enter case
     */
    private BillPaymentResponse toEnterResponse(String accountId, BillPaymentResult result, LocalDateTime now) {
        BillPaymentResponse base = billPaymentMapper.toResponse(accountId, result.message(), now);
        return new BillPaymentResponse(
                base.transactionName(),
                base.title01(),
                base.currentDate(),
                base.programName(),
                base.title02(),
                base.currentTime(),
                base.accountId(),
                result.newBalance(),
                base.errorMessage());
    }

    /**
     * Builds the back-navigation response for PF3, reproducing the COBOL
     * {@code RETURN-TO-PREV-SCREEN} transfer to the Main Menu ({@code COBIL00C}
     * L128-L135 and L273-L284).
     *
     * <p>The response body names the destination screen
     * ({@value #BACK_TRANSACTION_ID} / {@value #BACK_PROGRAM_NAME}) so the client
     * knows to render the Main Menu next; the matching
     * {@value #HEADER_NEXT_PROGRAM}/{@value #HEADER_NEXT_TRANSACTION} HTTP response
     * headers advertising the same target are added by the PF3 branch of
     * {@link #payBill(BillPaymentRequest)}. The date, time, and title lines are
     * reused from the mapper's header so the formatting stays identical across
     * screens. No account id, balance, or message is carried.</p>
     *
     * @param now the timestamp used to render the header date and time
     * @return the response signalling navigation to the Main Menu
     */
    private BillPaymentResponse toBackNavigationResponse(LocalDateTime now) {
        BillPaymentResponse base = billPaymentMapper.toResponse((String) null, null, now);
        return new BillPaymentResponse(
                BACK_TRANSACTION_ID,
                base.title01(),
                base.currentDate(),
                BACK_PROGRAM_NAME,
                base.title02(),
                base.currentTime(),
                null,
                null,
                null);
    }

    /**
     * Builds a blank Bill Payment screen &mdash; the shared shape used by first
     * entry ({@link #billPaymentScreen()}) and by the PF4 clear branch, both of
     * which present the standard header with no account id, no balance, and no
     * message ({@code INITIALIZE-ALL-FIELDS}, {@code COBIL00C} L560-L566).
     *
     * @param now the timestamp used to render the header date and time
     * @return a blank {@link BillPaymentResponse}
     */
    private BillPaymentResponse blankScreen(LocalDateTime now) {
        return billPaymentMapper.toResponse((String) null, null, now);
    }
}
