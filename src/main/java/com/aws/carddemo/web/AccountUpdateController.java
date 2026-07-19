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
import java.time.LocalDateTime;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.AccountUpdateRequest;
import com.aws.carddemo.dto.AccountUpdateResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.AccountMapper;
import com.aws.carddemo.service.AccountService;
import com.aws.carddemo.service.AccountService.AccountDetail;
import com.aws.carddemo.service.AccountService.AccountUpdateCommand;
import com.aws.carddemo.service.AccountService.AccountUpdateResult;
import com.aws.carddemo.service.AccountService.DateParts;
import com.aws.carddemo.service.AccountService.PhoneParts;
import com.aws.carddemo.service.AccountService.SsnParts;
import com.aws.carddemo.service.AccountService.Status;

/**
 * REST controller for the CardDemo <em>Account Update</em> screen &mdash; the Java
 * re-platform of COBOL online program {@code COACTUPC} (CICS transaction {@code CAUP},
 * source {@code legacy/cbl/COACTUPC.cbl}). {@code COACTUPC} is the largest online program
 * in CardDemo (~4236 lines) and drives an explicit
 * fetch&nbsp;&rarr;&nbsp;validate&nbsp;&rarr;&nbsp;confirm&nbsp;&rarr;&nbsp;save state
 * machine with extensive field edits.
 *
 * <h2>Thin controller</h2>
 * This controller is deliberately <strong>thin</strong>: it owns no business logic. Every
 * field edit, every comparison against the fetched record, the optimistic-lock concurrency
 * guard, and all persistence live in {@link AccountService}. The controller only:
 * <ol>
 *   <li>translates the submitted attention key ({@link PfKeyAction}) and the carried
 *       pseudo-conversational flow state into a service command;</li>
 *   <li>enforces the COBOL PF-key validity gate; and</li>
 *   <li>maps the {@link AccountUpdateResult} back onto the {@link AccountUpdateResponse}
 *       screen contract via {@link AccountMapper}.</li>
 * </ol>
 * No COACTUPC edit rule is re-implemented here (AAP &sect;0.5.3); the service returns the
 * caller-visible messages verbatim.
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.7.1 hotspot H1)</h2>
 * CardDemo online programs are pseudo-conversational: each screen submit is a discrete CICS
 * transaction whose flow state travels in the {@code COMMAREA}. HTTP is likewise stateless,
 * so the {@code COMMAREA} navigation/flow fields are carried explicitly as request/response
 * headers rather than server-side session state:
 * <table class="striped">
 *   <caption>COMMAREA field &rarr; HTTP header mapping</caption>
 *   <tr><th>COMMAREA field</th><th>Request header (in)</th><th>Response header (out)</th></tr>
 *   <tr><td>{@code ACUP-CHANGE-ACTION} flow state</td>
 *       <td>{@value #HEADER_PRIOR_STATUS}</td><td>{@value #HEADER_STATUS}</td></tr>
 *   <tr><td>{@code CDEMO-FROM-PROGRAM}</td>
 *       <td>{@value #HEADER_FROM_PROGRAM}</td><td>{@value #HEADER_NEXT_PROGRAM} (PF3)</td></tr>
 *   <tr><td>{@code CDEMO-FROM-TRANID}</td>
 *       <td>{@value #HEADER_FROM_TRANSACTION}</td><td>{@value #HEADER_NEXT_TRANSACTION} (PF3)</td></tr>
 *   <tr><td>{@code ACCT} {@code @Version} (optimistic lock)</td>
 *       <td>{@value #HEADER_ACCOUNT_VERSION}</td><td>{@value #HEADER_ACCOUNT_VERSION}</td></tr>
 * </table>
 * The client echoes the {@value #HEADER_STATUS} it last received back as
 * {@value #HEADER_PRIOR_STATUS} on the next submit, exactly as the terminal returned the
 * {@code COMMAREA}. A first entry carries no prior-status header, which the service treats
 * as {@code ACUP-DETAILS-NOT-FETCHED} (fetch-and-show).
 *
 * <h2>PF-key validity gate ({@code COACTUPC 0000-MAIN}, L905&ndash;916)</h2>
 * Before dispatching, the submitted key is validated exactly as the COBOL gate: only
 * {@link PfKeyAction#ENTER}, {@link PfKeyAction#PF3}, {@link PfKeyAction#PF5} (and only while
 * the state is {@link Status#CHANGES_OK_NOT_CONFIRMED}) and {@link PfKeyAction#PF12} (and only
 * once details have been fetched) are valid; any other or contextually-invalid key is coerced
 * to {@link PfKeyAction#ENTER}.
 *
 * <h2>Error surfacing</h2>
 * <ul>
 *   <li><b>Validation / flow outcomes</b> are carried inside {@link AccountUpdateResult} and
 *       rendered as {@code 200 OK} same-screen messages (the COBOL re-display of the map).</li>
 *   <li><b>Missing records</b> raise {@code RecordNotFoundException} &rarr; {@code 404}.</li>
 *   <li><b>Concurrent modification</b> lets {@code OptimisticLockingFailureException}
 *       propagate &rarr; {@code 409} (AAP &sect;0.7.1 H6). Both are handled centrally by the
 *       {@code GlobalExceptionHandler}; this controller uses no {@code try}/{@code catch}.</li>
 *   <li><b>Malformed request bodies</b> fail {@code @Valid} &rarr; {@code 400}.</li>
 * </ul>
 *
 * <h2>Sensitive data (AAP &sect;0.9.3)</h2>
 * The Social Security Number, date of birth, and government-issued id flow through this
 * controller for legacy display parity but are masked by the {@code toString()} of both the
 * request and response DTOs. This controller never logs the request or the response.
 *
 * <p>The controller holds only its injected, immutable collaborators and is therefore
 * stateless and thread-safe.</p>
 */
@RestController
@RequestMapping("/api/v1/accounts/update")
@Tag(name = "Account Update",
        description = "Account update (COBOL COACTUPC / CICS transaction CAUP): view and modify account and customer detail with field-level edit validation.")
public class AccountUpdateController {

    // ------------------------------------------------------------------------------------
    // Program identity and navigation constants (COBOL LIT-* literals, COACTUPC L533-560).
    // ------------------------------------------------------------------------------------

    /** COBOL {@code LIT-THISPGM}: this program's name. */
    private static final String PROGRAM_NAME = "COACTUPC";

    /** COBOL {@code LIT-THISTRANID}: this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CAUP";

    /** COBOL {@code LIT-MENUPGM}: the default back-navigation target (the main menu program). */
    private static final String DEFAULT_BACK_PROGRAM = "COMEN01C";

    /** COBOL {@code LIT-MENUTRANID}: the default back-navigation transaction id (main menu). */
    private static final String DEFAULT_BACK_TRANSACTION = "CM00";

    // ------------------------------------------------------------------------------------
    // Pseudo-conversational flow-state headers (the COMMAREA translation, see class contract).
    // ------------------------------------------------------------------------------------

    /** Request header carrying the flow state ({@code ACUP-CHANGE-ACTION}) from the prior turn. */
    private static final String HEADER_PRIOR_STATUS = "X-CardDemo-Prior-Status";

    /** Response header carrying the resulting flow state for the client to echo back. */
    private static final String HEADER_STATUS = "X-CardDemo-Status";

    /** Request header carrying the calling program ({@code CDEMO-FROM-PROGRAM}). */
    private static final String HEADER_FROM_PROGRAM = "X-CardDemo-From-Program";

    /** Request header carrying the calling transaction id ({@code CDEMO-FROM-TRANID}). */
    private static final String HEADER_FROM_TRANSACTION = "X-CardDemo-From-Transaction";

    /** Response header naming the program to navigate to on PF3 ({@code CDEMO-TO-PROGRAM}). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the transaction to navigate to on PF3 ({@code CDEMO-TO-TRANID}). */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * Request/response header carrying the {@code ACCT} optimistic-lock {@code @Version}
     * observed when details were fetched. Echoed back on the confirm (PF5) submit so the
     * service can detect a concurrent change (AAP &sect;0.7.1 H6). Optional: when absent the
     * cross-request check is skipped and the in-transaction {@code @Version} still applies.
     */
    private static final String HEADER_ACCOUNT_VERSION = "X-CardDemo-Account-Version";

    // ------------------------------------------------------------------------------------
    // Blank-screen display literals. On the first entry (and on PF3 navigation) COACTUPC
    // SENDs the map with only the header, footer, and a prompt populated. These mirror the
    // byte-exact literals the AccountMapper emits for a populated screen so the field-level
    // display contract is identical whether or not a record has been fetched (AAP §0.7.1 H2).
    // ------------------------------------------------------------------------------------

    /** First title line ({@code CCDA-TITLE01}, {@code COTTL01Y.cpy}, {@code X(40)}). */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /** Second title line ({@code CCDA-TITLE02}, {@code COTTL01Y.cpy}, {@code X(40)}). */
    private static final String TITLE_02 = "              CardDemo                  ";

    /** Primary function-key footer ({@code FKEYS} initial value, {@code COACTUP.bms}, {@code X(21)}). */
    private static final String FKEYS = "ENTER=Process F3=Exit";

    /** Save function-key label ({@code FKEY05} initial value, {@code X(7)}). */
    private static final String FKEY_SAVE = "F5=Save";

    /** Cancel function-key label ({@code FKEY12} initial value, {@code X(10)}). */
    private static final String FKEY_CANCEL = "F12=Cancel";

    /** Empty display value for the header date/time on a screen rendered without a timestamp. */
    private static final String EMPTY = "";

    /**
     * First-entry prompt ({@code COACTUPC} {@code WS-PROMPT-FOR-SEARCH-KEYS} /
     * {@code PROMPT-FOR-SEARCH-KEYS}) asking the operator for the account id to fetch. The
     * literal is reproduced here (the service constant is package-private) so the blank screen
     * shows the exact legacy prompt.
     */
    private static final String MSG_PROMPT_SEARCH_KEYS = "Enter or update id of account to update";

    // ------------------------------------------------------------------------------------
    // Collaborators (constructor-injected; no field injection, no Lombok).
    // ------------------------------------------------------------------------------------

    /** Owns every COACTUPC edit rule, the flow state machine, and persistence. */
    private final AccountService accountService;

    /** Decomposes/re-composes the entity fields into the split screen DTO fields. */
    private final AccountMapper accountMapper;

    /**
     * Creates the controller with its required collaborators.
     *
     * @param accountService the account inquiry/update service (never {@code null})
     * @param accountMapper  the entity&harr;DTO mapper (never {@code null})
     */
    public AccountUpdateController(AccountService accountService, AccountMapper accountMapper) {
        this.accountService = accountService;
        this.accountMapper = accountMapper;
    }

    // ====================================================================================
    // Endpoints
    // ====================================================================================

    /**
     * Renders the initial, blank Account Update screen prompting for the account id, matching
     * the COBOL first-entry path ({@code COACTUPC 0000-MAIN}, L964&ndash;973): on a fresh entry
     * ({@code EIBCALEN = 0} / arriving from the menu without re-entry, i.e. {@code REENTER=false})
     * the program simply SENDs the map with {@code ACUP-DETAILS-NOT-FETCHED} and the
     * "enter the account id" prompt &mdash; it performs no edit and reads no record.
     *
     * <p>No flow-state header is emitted: the absence of {@value #HEADER_PRIOR_STATUS} on the
     * subsequent submit is what tells the service this is the not-yet-fetched state.</p>
     *
     * @return {@code 200 OK} with a blank screen carrying the search-key prompt
     */
    @GetMapping
    @Operation(summary = "Blank Account Update screen",
            description = "First-entry screen prompting for an account id (COBOL COACTUPC initial SEND MAP).")
    public ResponseEntity<AccountUpdateResponse> showBlankScreen() {
        return ResponseEntity.ok(blankResponse(null, MSG_PROMPT_SEARCH_KEYS, null));
    }

    /**
     * Processes one submit of the Account Update screen, reproducing the COBOL
     * {@code COACTUPC 0000-MAIN} re-entry dispatch (every submit is a re-entry, so
     * {@code REENTER=true}). The method enforces the PF-key validity gate and then routes on
     * the (possibly coerced) attention key, delegating all edits, comparisons, and persistence
     * to {@link AccountService#updateAccount(AccountUpdateCommand, boolean)}.
     *
     * <h3>Routing (after the gate)</h3>
     * <ul>
     *   <li><b>PF3 (Exit)</b> &mdash; back-navigation: {@code 200 OK} with the
     *       {@value #HEADER_NEXT_PROGRAM}/{@value #HEADER_NEXT_TRANSACTION} headers set to the
     *       calling program/transaction (or the main menu when none was supplied). Reproduces the
     *       COBOL {@code SYNCPOINT} + {@code XCTL} (L927&ndash;959); no service call is made.</li>
     *   <li><b>ENTER</b> &mdash; the service decides from the carried flow state whether this is
     *       the first fetch ({@link Status#SHOW_DETAILS}) or a validate-changes pass
     *       ({@link Status#CHANGES_OK_NOT_CONFIRMED} / {@link Status#CHANGES_NOT_OK}).</li>
     *   <li><b>PF5 (Save)</b> &mdash; confirm-and-persist; only reachable while the prior state is
     *       {@link Status#CHANGES_OK_NOT_CONFIRMED}. On {@link Status#DONE} the success message is
     *       shown; a concurrent modification propagates as {@code 409}.</li>
     *   <li><b>PF12 (Cancel)</b> &mdash; discard pending edits and re-read the original record;
     *       only reachable once details have been fetched.</li>
     * </ul>
     *
     * @param request           the submitted screen fields and attention key (bean-validated)
     * @param priorStatusHeader the flow state from the previous turn; absent on first entry
     * @param fromProgram       the calling program for PF3 back-navigation; optional
     * @param fromTransaction   the calling transaction id for PF3 back-navigation; optional
     * @param versionHeader     the account {@code @Version} observed at fetch time; optional
     * @return {@code 200 OK} with the next screen state and the flow-state header; PF3 additionally
     *         sets the back-navigation headers
     */
    @PostMapping
    @Operation(summary = "Submit the Account Update screen",
            description = "Reproduces the COACTUPC EVALUATE EIBAID routing: fetch, validate, and rewrite the account and customer detail.")
    public ResponseEntity<AccountUpdateResponse> update(
            @Valid @RequestBody AccountUpdateRequest request,
            @RequestHeader(name = HEADER_PRIOR_STATUS, required = false) String priorStatusHeader,
            @RequestHeader(name = HEADER_FROM_PROGRAM, required = false) String fromProgram,
            @RequestHeader(name = HEADER_FROM_TRANSACTION, required = false) String fromTransaction,
            @RequestHeader(name = HEADER_ACCOUNT_VERSION, required = false) String versionHeader) {

        // Restore the carried flow state (COMMAREA ACUP-CHANGE-ACTION). A missing header is the
        // COBOL ACUP-DETAILS-NOT-FETCHED state, which the service treats as a fresh fetch.
        Status priorStatus = parseStatus(priorStatusHeader);
        boolean detailsFetched = priorStatus != null;

        // PF-key validity gate (COACTUPC 0000-MAIN L905-916): coerce any invalid key to ENTER.
        PfKeyAction effectiveKey = coercePfKey(request.action(), priorStatus, detailsFetched);

        // WHEN CCARD-AID-PFK03: SYNCPOINT + XCTL to the caller (or the main menu).
        if (effectiveKey == PfKeyAction.PF3) {
            return backNavigation(fromProgram, fromTransaction);
        }

        // The two pseudo-function-key intents the service honors: PF5 = confirm/save,
        // PF12 = cancel/re-read. Everything else is a plain ENTER (process inputs).
        boolean confirmSave = effectiveKey == PfKeyAction.PF5;
        boolean cancel = effectiveKey == PfKeyAction.PF12;

        // Straight field copy of the entered values into the service command (no business logic).
        AccountUpdateCommand command = buildCommand(
                request, priorStatus, parseVersion(versionHeader), confirmSave, cancel);

        // Every submit is a re-entry within the conversation (REENTER=true). Not-found records and
        // concurrent modifications propagate to the GlobalExceptionHandler (404 / 409); flow and
        // validation outcomes come back inside the result for a 200 same-screen re-display.
        AccountUpdateResult result = accountService.updateAccount(command, true);
        return renderResult(result, request.accountId());
    }

    // ====================================================================================
    // Routing helpers
    // ====================================================================================

    /**
     * Reproduces the COBOL PF-key validity gate ({@code COACTUPC 0000-MAIN}, L905&ndash;916).
     * A key is valid only when it is {@link PfKeyAction#ENTER}; {@link PfKeyAction#PF3};
     * {@link PfKeyAction#PF5} while the state is {@link Status#CHANGES_OK_NOT_CONFIRMED}; or
     * {@link PfKeyAction#PF12} once details have been fetched. Any other key &mdash; including a
     * {@code null} action or a contextually-invalid PF5/PF12 &mdash; is coerced to
     * {@link PfKeyAction#ENTER} (COBOL {@code SET CCARD-AID-ENTER TO TRUE}).
     *
     * @param action         the submitted attention key (may be {@code null})
     * @param priorStatus    the carried flow state ({@code null} when details are not fetched)
     * @param detailsFetched whether the details have been fetched in a prior turn
     * @return the effective, validated attention key to dispatch on
     */
    private PfKeyAction coercePfKey(PfKeyAction action, Status priorStatus, boolean detailsFetched) {
        if (action == PfKeyAction.ENTER || action == PfKeyAction.PF3) {
            return action;
        }
        if (action == PfKeyAction.PF5 && priorStatus == Status.CHANGES_OK_NOT_CONFIRMED) {
            return PfKeyAction.PF5;
        }
        if (action == PfKeyAction.PF12 && detailsFetched) {
            return PfKeyAction.PF12;
        }
        return PfKeyAction.ENTER;
    }

    /**
     * Handles the PF3 (Exit) back-navigation, reproducing {@code COACTUPC 0000-MAIN}
     * L927&ndash;959: the target is the calling program/transaction carried in the COMMAREA
     * ({@code CDEMO-FROM-PROGRAM}/{@code CDEMO-FROM-TRANID}); when either is blank the main menu
     * ({@code LIT-MENUPGM}/{@code LIT-MENUTRANID}) is used. The COBOL {@code SYNCPOINT} + {@code XCTL}
     * becomes a {@code 200 OK} carrying the destination in the navigation headers, and a blank
     * account-update screen body.
     *
     * @param fromProgram     the calling program (may be {@code null}/blank)
     * @param fromTransaction the calling transaction id (may be {@code null}/blank)
     * @return {@code 200 OK} with the {@value #HEADER_NEXT_PROGRAM}/{@value #HEADER_NEXT_TRANSACTION}
     *         headers set
     */
    private ResponseEntity<AccountUpdateResponse> backNavigation(String fromProgram,
                                                                 String fromTransaction) {
        String nextProgram = hasText(fromProgram) ? fromProgram.trim() : DEFAULT_BACK_PROGRAM;
        String nextTransaction =
                hasText(fromTransaction) ? fromTransaction.trim() : DEFAULT_BACK_TRANSACTION;

        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_NEXT_PROGRAM, nextProgram);
        headers.set(HEADER_NEXT_TRANSACTION, nextTransaction);
        return ResponseEntity.ok().headers(headers).body(blankResponse(null, null, null));
    }

    // ====================================================================================
    // Command construction (request DTO -> service command)
    // ====================================================================================

    /**
     * Builds the {@link AccountUpdateCommand} directly from the submitted request &mdash; a
     * straight, business-logic-free copy of the raw entered values. The split screen fields
     * (year/month/day dates, the three SSN parts, and the area/prefix/line phone parts) are
     * grouped into the service's component records, and the five monetary values are converted
     * from {@link BigDecimal} to their raw string form for the service's signed-decimal edit
     * ({@code 1250-EDIT-SIGNED-9V2}); no rounding or validation is performed here.
     *
     * @param request         the validated request DTO
     * @param priorStatus     the carried flow state (may be {@code null})
     * @param expectedVersion the account {@code @Version} observed at fetch time (may be {@code null})
     * @param confirmSave     whether the confirm key (PF5) was pressed
     * @param cancel          whether the cancel key (PF12) was pressed
     * @return the fully populated service command
     */
    private AccountUpdateCommand buildCommand(AccountUpdateRequest request,
                                              Status priorStatus,
                                              Long expectedVersion,
                                              boolean confirmSave,
                                              boolean cancel) {
        return new AccountUpdateCommand(
                request.accountId(),
                expectedVersion,
                request.accountStatus(),
                new DateParts(request.openYear(), request.openMonth(), request.openDay()),
                moneyToString(request.creditLimit()),
                new DateParts(request.expiryYear(), request.expiryMonth(), request.expiryDay()),
                moneyToString(request.cashLimit()),
                new DateParts(request.reissueYear(), request.reissueMonth(), request.reissueDay()),
                moneyToString(request.currentBalance()),
                moneyToString(request.currentCycleCredit()),
                moneyToString(request.currentCycleDebit()),
                request.groupId(),
                new SsnParts(request.ssnPart1(), request.ssnPart2(), request.ssnPart3()),
                new DateParts(request.dobYear(), request.dobMonth(), request.dobDay()),
                request.ficoScore(),
                request.firstName(),
                request.middleName(),
                request.lastName(),
                request.addressLine1(),
                request.addressLine2(),
                request.city(),
                request.stateCode(),
                request.zipCode(),
                request.countryCode(),
                new PhoneParts(request.phone1Area(), request.phone1Prefix(), request.phone1Line()),
                new PhoneParts(request.phone2Area(), request.phone2Prefix(), request.phone2Line()),
                request.governmentId(),
                request.eftAccountId(),
                request.primaryHolderFlag(),
                priorStatus,
                confirmSave,
                cancel);
    }

    // ====================================================================================
    // Result rendering (service result -> screen response)
    // ====================================================================================

    /**
     * Maps an {@link AccountUpdateResult} onto the {@link AccountUpdateResponse} screen contract,
     * emitting the resulting flow state (and, when a record is present, its optimistic-lock
     * version) as response headers so the client can echo them back on the next submit.
     *
     * <p>The single caller-visible message is routed to the informational or the error field
     * according to the outcome: the success/prompt states populate the info line, while the
     * validation-failure and lock/update-failure states populate the error line &mdash; matching
     * the COBOL {@code WS-INFO-MSG} versus {@code WS-RETURN-MSG}/{@code CCARD-ERROR-MSG} split.</p>
     *
     * @param result        the service outcome (never {@code null})
     * @param accountIdEcho the account id the operator entered, echoed on a blank/error screen
     *                      that has no fetched record to display
     * @return {@code 200 OK} with the rendered screen and the flow-state header(s)
     */
    private ResponseEntity<AccountUpdateResponse> renderResult(AccountUpdateResult result,
                                                               String accountIdEcho) {
        Status status = result.status();
        boolean error = isErrorStatus(status);
        String infoMessage = error ? null : result.message();
        String errorMessage = error ? result.message() : null;

        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_STATUS, status.name());

        AccountDetail detail = result.detail();
        AccountUpdateResponse body;
        if (detail == null) {
            // No record to display (for example a blank/invalid account-filter error before any
            // fetch): re-show the entered account id alongside the message.
            body = blankResponse(accountIdEcho, infoMessage, errorMessage);
        } else {
            Account account = detail.account();
            Customer customer = detail.customer();
            body = accountMapper.toUpdateResponse(
                    account, customer, infoMessage, errorMessage, LocalDateTime.now());
            Long version = account.getVersion();
            if (version != null) {
                headers.set(HEADER_ACCOUNT_VERSION, version.toString());
            }
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Builds a blank Account Update screen &mdash; the header, function-key footer, and an
     * optional prompt/message, with every account/customer data field left empty. Used for the
     * first-entry prompt, PF3 navigation, and any pre-fetch input error that has no record to
     * show. The header and footer literals mirror those the {@link AccountMapper} emits for a
     * populated screen so the field-level display contract is identical (AAP &sect;0.7.1 H2).
     *
     * @param accountIdEcho the account id to echo (may be {@code null})
     * @param infoMessage   the informational message (may be {@code null})
     * @param errorMessage  the error message (may be {@code null})
     * @return a blank {@link AccountUpdateResponse}
     */
    private AccountUpdateResponse blankResponse(String accountIdEcho,
                                                String infoMessage,
                                                String errorMessage) {
        return new AccountUpdateResponse(
                TRANSACTION_ID,   // transactionName
                TITLE_01,         // title01
                TITLE_02,         // title02
                EMPTY,            // currentDate
                PROGRAM_NAME,     // programName
                EMPTY,            // currentTime
                accountIdEcho,    // accountId
                null,             // accountStatus
                null,             // openYear
                null,             // openMonth
                null,             // openDay
                null,             // creditLimit
                null,             // expiryYear
                null,             // expiryMonth
                null,             // expiryDay
                null,             // cashLimit
                null,             // reissueYear
                null,             // reissueMonth
                null,             // reissueDay
                null,             // currentBalance
                null,             // currentCycleCredit
                null,             // groupId
                null,             // currentCycleDebit
                null,             // customerId
                null,             // ssnPart1
                null,             // ssnPart2
                null,             // ssnPart3
                null,             // dobYear
                null,             // dobMonth
                null,             // dobDay
                null,             // ficoScore
                null,             // firstName
                null,             // middleName
                null,             // lastName
                null,             // addressLine1
                null,             // stateCode
                null,             // addressLine2
                null,             // zipCode
                null,             // city
                null,             // countryCode
                null,             // phone1Area
                null,             // phone1Prefix
                null,             // phone1Line
                null,             // governmentId
                null,             // phone2Area
                null,             // phone2Prefix
                null,             // phone2Line
                null,             // eftAccountId
                null,             // primaryHolderFlag
                infoMessage,      // infoMessage
                errorMessage,     // errorMessage
                FKEYS,            // functionKeys
                FKEY_SAVE,        // functionKeySave
                FKEY_CANCEL);     // functionKeyCancel
    }

    // ====================================================================================
    // Small, side-effect-free helpers
    // ====================================================================================

    /**
     * Classifies a flow state as an error (screen-error line) or a non-error (info line)
     * outcome. The validation-failure and lock/update-failure states are errors; the
     * fetch/confirm-prompt and success states are informational.
     *
     * @param status the flow state
     * @return {@code true} when the message should render on the error line
     */
    private static boolean isErrorStatus(Status status) {
        return switch (status) {
            case CHANGES_NOT_OK, LOCK_ERROR, UPDATE_FAILED, CONCURRENT_CHANGE -> true;
            case SHOW_DETAILS, CHANGES_OK_NOT_CONFIRMED, DONE -> false;
        };
    }

    /**
     * Parses the carried flow-state header into a {@link Status}, without throwing: a
     * {@code null}, blank, or unrecognized value yields {@code null} (the COBOL
     * {@code ACUP-DETAILS-NOT-FETCHED} state). The lookup is an exact, case-sensitive match on
     * the enum constant name.
     *
     * @param header the raw header value (may be {@code null})
     * @return the matching {@link Status}, or {@code null} when none matches
     */
    private static Status parseStatus(String header) {
        if (!hasText(header)) {
            return null;
        }
        String name = header.trim();
        for (Status candidate : Status.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Parses the optimistic-lock version header into a {@link Long}, without throwing: a
     * {@code null}, blank, or non-numeric value yields {@code null} (skip the cross-request
     * concurrency check).
     *
     * @param header the raw header value (may be {@code null})
     * @return the parsed non-negative version, or {@code null} when absent/invalid
     */
    private static Long parseVersion(String header) {
        if (!hasText(header)) {
            return null;
        }
        String value = header.trim();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return Long.valueOf(value);
    }

    /**
     * Converts a monetary {@link BigDecimal} to the raw string form the service edits expect,
     * preserving the fixed scale via {@link BigDecimal#toPlainString()} (never scientific
     * notation). A {@code null} amount maps to {@code null} (an absent field).
     *
     * @param amount the monetary amount (may be {@code null})
     * @return the plain string form, or {@code null} when absent
     */
    private static String moneyToString(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    /**
     * Returns {@code true} when the value is non-{@code null} and contains a non-whitespace
     * character.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} when there is visible text
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

