/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.dto.AccountViewRequest;
import com.aws.carddemo.dto.AccountViewResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.AccountMapper;
import com.aws.carddemo.service.AccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

/**
 * REST controller for the CardDemo <strong>Account View</strong> screen &mdash;
 * the Java re-platform of the online COBOL program {@code COACTVWC} (CICS
 * transaction {@code CAVW}, source {@code legacy/cbl/COACTVWC.cbl}). It accepts
 * an eleven-digit account id and returns the merged account and owning-customer
 * detail that the legacy {@code CACTVWA} 3270 map displayed.
 *
 * <h2>Pseudo-conversational translation (AAP &sect;0.7.1 H1)</h2>
 * The CICS pseudo-conversational model of {@code COACTVWC} &mdash;
 * {@code RECEIVE} the map, {@code EVALUATE EIBAID}, then {@code SEND} the map or
 * {@code XCTL} to another program and {@code RETURN TRANSID} &mdash; is
 * translated to an explicit, stateless request/response flow:
 * <ul>
 *   <li>The blank first-entry screen (the COBOL {@code CDEMO-PGM-ENTER}
 *       first-time path that {@code 1000-SEND-MAP}s an empty map with the
 *       "enter account id" prompt) is served by {@link #initialScreen()}.</li>
 *   <li>Each screen submit (the COBOL {@code CDEMO-PGM-REENTER} re-entry path)
 *       is a stateless {@code POST} handled by
 *       {@link #view(AccountViewRequest, String, String)}, which reproduces the
 *       {@code EVALUATE EIBAID} routing.</li>
 *   <li>The {@code COMMAREA}-carried navigation context
 *       ({@code CDEMO-FROM-PROGRAM} / {@code CDEMO-FROM-TRANID}) becomes explicit
 *       request/response headers rather than hidden shared storage.</li>
 * </ul>
 *
 * <h2>PF-key routing (parity with {@code COACTVWC})</h2>
 * The legacy program treats only {@code ENTER} and {@code PF3} as valid
 * attention identifiers. This controller preserves those observable outcomes:
 * <ul>
 *   <li><b>{@code ENTER}</b> (and an unspecified key, which the COBOL remaps to
 *       {@code ENTER}) &mdash; fetch and display the requested account.</li>
 *   <li><b>{@code PF3}</b> &mdash; exit back to the calling program, or to the
 *       main menu ({@code COMEN01C} / {@code CM00}) when no caller is recorded,
 *       reproducing the COBOL {@code XCTL} back-navigation.</li>
 *   <li><b>any other key</b> &mdash; redisplay the screen with the standard
 *       "invalid key" message.</li>
 * </ul>
 *
 * <h2>Sensitive data (AAP &sect;0.9.3)</h2>
 * The account-view contract includes the customer social-security number, date
 * of birth, and government-issued id. These remain fields on
 * {@link AccountViewResponse} for legacy display parity, but they are masked by
 * that DTO's {@code toString()} and this controller never logs the request or
 * the response.
 *
 * <p>This controller holds no mutable state and is therefore thread-safe. All
 * business logic lives in {@link AccountService}; all entity-to-DTO field
 * movement lives in {@link AccountMapper}. Dependencies are supplied by
 * constructor injection (no field injection, no Lombok).</p>
 */
@RestController
@RequestMapping("/api/v1/accounts/view")
@Tag(name = "Account View",
        description = "Account inquiry (COBOL COACTVWC / CICS transaction CAVW): "
                + "view merged account and customer detail.")
public class AccountViewController {

    /**
     * This program's name, echoed into the screen header ({@code LIT-THISPGM} in
     * {@code COACTVWC}, {@code PIC X(8)}).
     */
    private static final String THIS_PROGRAM = "COACTVWC";

    /**
     * This program's CICS transaction id, echoed into the screen header
     * ({@code LIT-THISTRANID} in {@code COACTVWC}, {@code PIC X(4)}).
     */
    private static final String THIS_TRANSACTION = "CAVW";

    /**
     * Default back-navigation target program when no caller is recorded &mdash;
     * the main menu ({@code LIT-MENUPGM} in {@code COACTVWC}, {@code PIC X(8)}).
     */
    private static final String DEFAULT_BACK_PROGRAM = "COMEN01C";

    /**
     * Default back-navigation target transaction when no caller is recorded
     * ({@code LIT-MENUTRANID} in {@code COACTVWC}, {@code PIC X(4)}).
     */
    private static final String DEFAULT_BACK_TRANSACTION = "CM00";

    /**
     * First screen title line, byte-exact from {@code CCDA-TITLE01}
     * ({@code legacy/cpy/COTTL01Y.cpy}, {@code PIC X(40)}).
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title line, byte-exact from {@code CCDA-TITLE02}
     * ({@code legacy/cpy/COTTL01Y.cpy}, {@code PIC X(40)}).
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * Informational prompt shown on the blank first-entry screen
     * ({@code WS-PROMPT-FOR-INPUT} in {@code COACTVWC}).
     */
    private static final String PROMPT_FOR_INPUT = "Enter or update id of account to display";

    /**
     * Standard "invalid key" message shown when a key other than {@code ENTER}
     * or {@code PF3} is transmitted ({@code CCDA-MSG-INVALID-KEY} in
     * {@code legacy/cpy/CSMSG01Y.cpy}).
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * Response header carrying the program to navigate to next &mdash; the
     * explicit translation of the COBOL {@code CDEMO-TO-PROGRAM} set before an
     * {@code XCTL}.
     */
    private static final String NEXT_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /**
     * Response header carrying the transaction to navigate to next &mdash; the
     * explicit translation of the COBOL {@code CDEMO-TO-TRANID}.
     */
    private static final String NEXT_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    /**
     * Optional request header carrying the calling program &mdash; the explicit
     * translation of the COMMAREA {@code CDEMO-FROM-PROGRAM} consulted by the
     * COBOL {@code PF3} back-navigation.
     */
    private static final String FROM_PROGRAM_HEADER = "X-CardDemo-From-Program";

    /**
     * Optional request header carrying the calling transaction &mdash; the
     * explicit translation of the COMMAREA {@code CDEMO-FROM-TRANID}.
     */
    private static final String FROM_TRANSACTION_HEADER = "X-CardDemo-From-Transaction";

    /**
     * Date mask for the screen header, byte-identical to the
     * {@code WS-CURDATE-MM-DD-YY} {@code MM/DD/YY} rendering of
     * {@code legacy/cpy/CSDAT01Y.cpy}.
     */
    private static final DateTimeFormatter DATE_MM_DD_YY = DateTimeFormatter.ofPattern("MM/dd/uu");

    /**
     * Time mask for the screen header, byte-identical to the
     * {@code WS-CURTIME-HH-MM-SS} {@code HH:MM:SS} rendering of
     * {@code legacy/cpy/CSDAT01Y.cpy}.
     */
    private static final DateTimeFormatter TIME_HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Business-logic collaborator: the account inquiry/update service. */
    private final AccountService accountService;

    /** Field-movement collaborator: entity-to-DTO mapper for the view screen. */
    private final AccountMapper accountMapper;

    /**
     * Creates the controller with its collaborators supplied by Spring through
     * constructor injection. This is the only constructor, so no
     * {@code @Autowired} annotation is required.
     *
     * @param accountService the account inquiry service (must not be {@code null})
     * @param accountMapper  the account view mapper (must not be {@code null})
     */
    public AccountViewController(AccountService accountService, AccountMapper accountMapper) {
        this.accountService = accountService;
        this.accountMapper = accountMapper;
    }

    /**
     * Serves the blank first-entry Account View screen, reproducing the COBOL
     * {@code CDEMO-PGM-ENTER} first-time path of {@code COACTVWC}: an empty map
     * carrying the screen header (transaction id, program name, current date and
     * time) and the informational prompt to enter an account id. No account is
     * read on first entry and no error is present.
     *
     * @return {@code 200 OK} with a blank {@link AccountViewResponse} that holds
     *         only the header fields and the "enter account id" prompt
     */
    @GetMapping
    @Operation(summary = "Blank Account View screen",
            description = "First-entry screen with the prompt to enter an account id "
                    + "(COBOL COACTVWC initial SEND MAP).")
    public ResponseEntity<AccountViewResponse> initialScreen() {
        return ResponseEntity.ok(blankScreen(PROMPT_FOR_INPUT, null, LocalDateTime.now()));
    }

    /**
     * Handles a screen submit for the Account View screen, reproducing the
     * {@code EVALUATE EIBAID} routing of {@code COACTVWC}. The request is
     * structurally validated by {@code @Valid}: the account id must be present
     * and one-to-eleven decimal digits (the BMS {@code MUSTFILL} numeric
     * contract), so a blank or non-numeric id is a gross format violation that
     * the {@code GlobalExceptionHandler} renders as {@code 400 Bad Request}.
     *
     * <p>Routing on the transmitted attention identifier
     * ({@link AccountViewRequest#action()}):</p>
     * <ul>
     *   <li><b>{@code PF3}</b> &mdash; back-navigation. Returns {@code 200 OK}
     *       with the {@value #NEXT_PROGRAM_HEADER} / {@value #NEXT_TRANSACTION_HEADER}
     *       headers set to the caller ({@value #FROM_PROGRAM_HEADER} /
     *       {@value #FROM_TRANSACTION_HEADER}) when supplied, otherwise to the
     *       main menu ({@value #DEFAULT_BACK_PROGRAM} / {@value #DEFAULT_BACK_TRANSACTION}).
     *       This is the REST analog of the COBOL {@code XCTL}, which transfers
     *       control and therefore sends no account-view body.</li>
     *   <li><b>{@code ENTER} (or an unspecified key)</b> &mdash; fetch the
     *       account. The id is parsed to its numeric key and passed to
     *       {@link AccountService#viewAccount(long)}; the merged result is mapped
     *       to the response. A zero or out-of-range account filter (for example
     *       {@code "00000000000"}, which passes the {@code @Valid} digit pattern)
     *       raises {@link IllegalArgumentException} carrying the COBOL edit message;
     *       that is a same-screen field edit, so it is caught and redisplayed at
     *       {@code 200 OK} with the verbatim message. A missing account/customer
     *       instead surfaces as {@code RecordNotFoundException}, which is
     *       intentionally <em>not</em> caught here so it propagates to the
     *       {@code GlobalExceptionHandler} and becomes {@code 404 Not Found}
     *       &mdash; the caller-visible parity of the COBOL "account not found"
     *       outcome (AAP &sect;0.7.2 M1).</li>
     *   <li><b>any other key</b> &mdash; redisplay the screen with the standard
     *       "invalid key" message and {@code 200 OK}.</li>
     * </ul>
     *
     * <p>Neither the request nor the response is logged, because the response
     * contract carries sensitive customer identifiers (AAP &sect;0.9.3).</p>
     *
     * @param request         the submitted Account View request (validated)
     * @param fromProgram     the calling program from the navigation context;
     *                        may be {@code null} when there is no caller
     * @param fromTransaction the calling transaction from the navigation context;
     *                        may be {@code null} when there is no caller
     * @return the next screen state: the account detail on {@code ENTER}, the
     *         navigation headers on {@code PF3}, or the redisplayed screen with a
     *         message otherwise
     */
    @PostMapping
    @Operation(summary = "Submit the Account View screen",
            description = "Routes on the transmitted key: ENTER fetches the account, "
                    + "PF3 navigates back, any other key redisplays with an invalid-key message.")
    public ResponseEntity<AccountViewResponse> view(
            @Valid @RequestBody AccountViewRequest request,
            @RequestHeader(value = FROM_PROGRAM_HEADER, required = false) String fromProgram,
            @RequestHeader(value = FROM_TRANSACTION_HEADER, required = false) String fromTransaction) {

        PfKeyAction action = request.action();

        if (action == PfKeyAction.PF3) {
            // XCTL back to the calling program, or to the main menu when none is recorded
            // (COBOL: CDEMO-FROM-PROGRAM/TRANID else LIT-MENUPGM/LIT-MENUTRANID).
            String nextProgram = hasText(fromProgram) ? fromProgram.trim() : DEFAULT_BACK_PROGRAM;
            String nextTransaction =
                    hasText(fromTransaction) ? fromTransaction.trim() : DEFAULT_BACK_TRANSACTION;
            return ResponseEntity.ok()
                    .header(NEXT_PROGRAM_HEADER, nextProgram)
                    .header(NEXT_TRANSACTION_HEADER, nextTransaction)
                    .build();
        }

        if (action == null || action == PfKeyAction.ENTER) {
            // ENTER (the COBOL remaps any unrecognized key to ENTER): fetch and display.
            // @Valid guarantees the id is 1-11 decimal digits, so parsing cannot fail or overflow.
            long accountId = Long.parseLong(request.accountId());
            AccountService.AccountDetail detail;
            try {
                detail = accountService.viewAccount(accountId);
            } catch (IllegalArgumentException ex) {
                // COBOL COACTVWC same-screen field edit: a zero or out-of-range account
                // filter (for example "00000000000", which passes the @Valid digit pattern)
                // is not a server fault but a screen edit, so it is redisplayed at 200 OK
                // carrying the verbatim edit message ("Account Filter must  be a non-zero
                // 11 digit number"). A missing account instead raises RecordNotFoundException
                // (NOT an IllegalArgumentException), which is deliberately left uncaught so it
                // propagates to the GlobalExceptionHandler and becomes 404 -- preserving the
                // COBOL "account not found" caller-visible parity (AAP 0.7.2 M1).
                return ResponseEntity.ok(blankScreen(null, ex.getMessage(), LocalDateTime.now()));
            }
            // COBOL COACTVWC 1200-SETUP-SCREEN-VARS runs on every send and, when no other
            // info message is set, defaults INFOMSGO to WS-PROMPT-FOR-INPUT ("Enter or update
            // id of account to display"); the WS-INFORM-OUTPUT 88-level is never SET, so the
            // populated detail screen carries the same persistent input prompt. Emit it here so
            // the success response's INFOMSGO matches the legacy screen exactly (F-P9-F).
            AccountViewResponse response = accountMapper.toViewResponse(
                    detail.account(), detail.customer(), PROMPT_FOR_INPUT, null, LocalDateTime.now());
            return ResponseEntity.ok(response);
        }

        // Any other attention key: redisplay the screen with the standard invalid-key message.
        return ResponseEntity.ok(blankScreen(null, INVALID_KEY_MESSAGE, LocalDateTime.now()));
    }

    /**
     * Builds a header-only {@link AccountViewResponse} for the screens that carry
     * no account detail: the blank first-entry screen and the invalid-key
     * redisplay. It populates the transaction id, program name, both title lines,
     * and the current date and time (formatted exactly as the legacy
     * {@code 1100-SCREEN-INIT} header), leaves every account and customer field
     * empty, and applies the supplied informational and error messages.
     *
     * @param infoMessage  the informational prompt, or {@code null} when none
     * @param errorMessage the error message, or {@code null} when none
     * @param now          the timestamp used for the header date and time
     * @return a fully constructed {@link AccountViewResponse} with only header
     *         and message fields populated
     */
    private AccountViewResponse blankScreen(String infoMessage, String errorMessage,
                                            LocalDateTime now) {
        return new AccountViewResponse(
                // --- Screen header / system fields ---
                THIS_TRANSACTION,            // transactionName
                SCREEN_TITLE_01,             // title01
                now.format(DATE_MM_DD_YY),   // currentDate
                THIS_PROGRAM,                // programName
                SCREEN_TITLE_02,             // title02
                now.format(TIME_HH_MM_SS),   // currentTime
                // --- Account detail fields (none on a blank / redisplay screen) ---
                null,                        // accountId
                null,                        // accountStatus
                null,                        // dateOpened
                null,                        // creditLimit
                null,                        // expiryDate
                null,                        // cashLimit
                null,                        // reissueDate
                null,                        // currentBalance
                null,                        // currentCycleCredit
                null,                        // groupId
                null,                        // currentCycleDebit
                // --- Customer detail fields (none on a blank / redisplay screen) ---
                null,                        // customerId
                null,                        // ssn
                null,                        // dateOfBirth
                null,                        // ficoScore
                null,                        // firstName
                null,                        // middleName
                null,                        // lastName
                null,                        // addressLine1
                null,                        // stateCode
                null,                        // addressLine2
                null,                        // zipCode
                null,                        // city
                null,                        // countryCode
                null,                        // phone1
                null,                        // governmentId
                null,                        // phone2
                null,                        // eftAccountId
                null,                        // primaryHolderFlag
                // --- Screen message fields ---
                infoMessage,                 // infoMessage
                errorMessage);               // errorMessage
    }

    /**
     * Returns {@code true} when the value is non-{@code null} and contains at
     * least one non-whitespace character. Used to test the optional
     * navigation-context headers without importing a framework string utility
     * (which is not among this file's declared dependencies).
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when the value has visible text
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
