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
package com.aws.carddemo.web.controller;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.DateStruct;
import com.aws.carddemo.dto.screen.COBIL00Form;
import com.aws.carddemo.service.online.BillPayService;
import com.aws.carddemo.service.online.BillPayService.AidKey;
import com.aws.carddemo.service.online.BillPayService.BillPayResult;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.ScreenTitles;
import com.aws.carddemo.web.support.ConfirmationTokenService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the AWS CardDemo online bill-payment screen.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COBIL00C.cbl} (CICS tran CB00, mapset
 * COBIL00). Bill pay - full balance payment.</p>
 *
 * <p>This class is the web-tier replacement for the CICS transaction {@code CB00}
 * whose program is {@code COBIL00C} (verified in {@code legacy/csd/CARDDEMO.CSD}:
 * {@code DEFINE TRANSACTION(CB00) ... PROGRAM(COBIL00C)}). It renders the
 * bill-payment screen ({@code COBIL0A} of mapset {@code COBIL00}, view
 * {@code COBIL00}), accepts an account id plus a {@code Y}/{@code N} confirmation,
 * and drives the full-balance payment. The COBOL {@code XCTL PROGRAM(...)} of
 * {@code RETURN-TO-PREV-SCREEN} is mirrored by a Spring MVC {@code redirect:} to the
 * target program's route (AAP &sect;0.3.3, &sect;0.4.1).</p>
 *
 * <h2>Presentation-only responsibility</h2>
 * <p>This controller performs presentation and navigation only. All business logic -
 * account read-update-rewrite, the payment transaction write, the next-transaction-id
 * derivation, the current timestamp, the confirm ({@code Y}/{@code N}) gating, and the
 * balance arithmetic - lives in {@link BillPayService} (the migration of the
 * {@code COBIL00C} business paragraphs). No repository is touched and no monetary
 * arithmetic is performed here, so this class declares no {@code BigDecimal} and never
 * uses {@code float}/{@code double}. The COBOL paragraphs map as follows:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} (the {@code EIBCALEN}/{@code CDEMO-PGM-REENTER} branch plus
 *       {@code EVALUATE EIBAID}) &rarr; {@link BillPayService#mainEntry(AidKey, COBIL00Form)},
 *       reached from both {@link #showBillPay(COBIL00Form)} and
 *       {@link #submitBillPay(COBIL00Form, String)}.</li>
 *   <li>{@code RECEIVE-BILLPAY-SCREEN} &rarr; Spring form binding of {@link COBIL00Form}
 *       on {@code POST /billpay}.</li>
 *   <li>{@code SEND-BILLPAY-SCREEN} / {@code POPULATE-HEADER-INFO} &rarr;
 *       {@link #populateHeader(COBIL00Form)} plus the {@code COBIL00} view render.</li>
 *   <li>The {@code EVALUATE EIBAID} key resolution &rarr; {@link #resolvePfKey(String)}
 *       and {@link #toAidKey(PfKey)}.</li>
 * </ul>
 *
 * <h2>Delegation contract</h2>
 * <p>{@link BillPayService#mainEntry(AidKey, COBIL00Form)} is the service's documented,
 * {@code @Transactional} controller-facing entry point: it owns the whole
 * {@code MAIN-PARA} control flow (first-entry detection, the first-display pre-selection,
 * and the {@code ENTER}/{@code PF3}/{@code PF4}/other dispatch, including the private
 * {@code RETURN-TO-PREV-SCREEN} hand-off). This controller therefore resolves the pressed
 * key to a {@link AidKey}, delegates to {@code mainEntry}, and interprets the returned
 * {@link BillPayResult}: a redirect outcome (the COBOL {@code XCTL}) is mapped to a
 * {@code redirect:} for the named program, while a redisplay outcome re-renders the
 * {@code COBIL00} screen with the (green/neutral/error) message on the {@code ERRMSG}
 * line. This keeps the controller thin and the read-write-update payment sequence a
 * single atomic unit of work inside the service transaction (AAP &sect;0.6.1).</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The CICS {@code COMMAREA} ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}, shared with {@link BillPayService} so both observe the same
 * per-session navigation and selection state. First entry ({@code EIBCALEN = 0}), the
 * {@code CDEMO-PGM-CONTEXT} enter/re-enter flag, the pre-selected account
 * ({@code CDEMO-CB00-TRN-SELECTED}, mapped to {@link CardDemoContext#getAcctId()}), and
 * the {@code from*}/{@code to*} hand-off are all handled inside {@code mainEntry}; this
 * controller only records the target transaction id on the context before a redirect.</p>
 *
 * <h2>Full-balance payment; no feature expansion (AAP &sect;0.2.2)</h2>
 * <p>{@code COBIL00C} always pays the <em>entire</em> current balance; there is
 * deliberately no partial-amount input, and the fixed merchant id {@code 999999999} is a
 * service concern. This controller introduces no partial-amount field and no additional
 * inputs beyond the account id and the confirmation carried on {@link COBIL00Form}.</p>
 *
 * <h2>Exception handling (AAP &sect;0.6.5)</h2>
 * <p>Typed exceptions raised by the service tier -
 * {@code RecordNotFoundException} (account/cross-reference not found),
 * {@code DuplicateKeyException} (duplicate transaction id) and {@code LogicError} - are
 * deliberately <em>not</em> caught here; they propagate to
 * {@code com.aws.carddemo.exception.GlobalExceptionHandler}.</p>
 *
 * <h2>Program &rarr; route mapping</h2>
 * <table border="1">
 *   <caption>Redirect target program &rarr; tran id / route (COBOL {@code XCTL} targets)</caption>
 *   <tr><th>Program</th><th>Tran</th><th>Route</th><th>Source</th></tr>
 *   <tr><td>COSGN00C</td><td>CC00</td><td>/signon</td><td>first entry / default</td></tr>
 *   <tr><td>COMEN01C</td><td>CM00</td><td>/menu</td><td>PF3 back</td></tr>
 * </table>
 *
 * <p>Constructor dependency injection, {@code private final} collaborators, no Lombok, no
 * field injection; a {@link Controller} (never {@code @RestController}) returning logical
 * Thymeleaf view names, so no REST/JSON surface is introduced (AAP &sect;0.3.4). Compiles
 * warning-free under {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see BillPayService
 * @see CardDemoContext
 * @see COBIL00Form
 */
@Controller
public class BillPayController {

    /** Logical Thymeleaf view name; resolves to {@code templates/COBIL00.html}. */
    private static final String VIEW_BILLPAY = "COBIL00";

    /**
     * Neutral banner shown when data binding rejects a field for exceeding its declared
     * {@code @Size} width (review finding #11). Only reachable by a crafted client (the template
     * {@code maxlength} / 3270 field width makes it impossible otherwise), so it carries no COBOL
     * business message and simply re-displays the screen without performing any payment.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    /** Web route for this screen (COBOL tran {@code CB00}); GET displays, POST submits. */
    private static final String PATH_BILLPAY = "/billpay";

    /** COBOL {@code WS-TRANID VALUE 'CB00'} - this screen's CICS transaction id. */
    private static final String TRANSACTION_ID = "CB00";

    /** COBOL {@code WS-PGMNAME VALUE 'COBIL00C'} - this screen's program name. */
    private static final String PROGRAM_NAME = "COBIL00C";

    /** Spring MVC redirect prefix used to mirror the COBOL {@code XCTL}. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Model attribute name bound by the {@code COBIL00} template ({@code th:object="${form}"}). */
    private static final String MODEL_ATTR_FORM = "form";

    /** Request-parameter name carrying the activated PF-key from the {@code COBIL00} form. */
    private static final String PARAM_PFKEY = "pfkey";

    /**
     * Confirmation-integrity operation label for the CB00 bill payment (review finding F12). Binds
     * the confirm nonce to this gesture so a nonce armed for another screen cannot be spent here.
     */
    private static final String OP_BILLPAY = "BILLPAY";

    /**
     * Web-tier confirmation-integrity banner (review finding F12). Shown when a {@code confirm=Y}
     * payment cannot be validated against the server-owned pending confirmation (missing/forged
     * nonce, replay, or a swapped account); no payment is made and the confirm prompt is re-armed.
     * This has no COBOL origin - it restores, over HTTP, the BMS protected-confirmation-field
     * contract the 3270 terminal enforced implicitly.
     */
    private static final String MSG_CONFIRM_INTEGRITY =
            "Confirmation could not be validated. Please review and confirm again.";

    /** Web PF-key token for the ENTER action (see {@code COBIL00.html} submit button). */
    private static final String WEB_KEY_ENTER = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code "PF3"}). */
    private static final String WEB_KEY_PF_PREFIX = "PF";

    /** CICS AID mnemonic for the ENTER key, understood by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix for program-function keys ({@code DFHPFnn}). */
    private static final String AID_PF_PREFIX = "DFHPF";

    /** Sign-on program (COBOL literal {@code 'COSGN00C'}); the first-entry / default target. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Sign-on transaction id ({@code CC00}); recorded on the context for the sign-on hand-off. */
    private static final String SIGNON_TRANID = "CC00";

    /** Sign-on route (mirrors {@code XCTL COSGN00C}). */
    private static final String ROUTE_SIGNON = "/signon";

    /** Main-menu program (COBOL literal {@code 'COMEN01C'}); the PF3 back target. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Main-menu transaction id ({@code CM00}); recorded on the context for the PF3 hand-off. */
    private static final String MENU_TRANID = "CM00";

    /** Main-menu route (mirrors {@code XCTL COMEN01C}). */
    private static final String ROUTE_MENU = "/menu";

    /**
     * Fallback redirect target - the main menu ({@code COMEN01C} / {@code CM00} /
     * {@code /menu}). Used when {@code mainEntry} hands off to an unmapped program name,
     * reproducing the COBOL {@code RETURN-TO-PREV-SCREEN} default of routing to the main
     * menu when the originating program is unknown.
     */
    private static final Target DEFAULT_TARGET = new Target(MENU_TRANID, ROUTE_MENU);

    /**
     * Authoritative mapping from the COBOL {@code XCTL} target program name (recorded by
     * {@link BillPayService#mainEntry(AidKey, COBIL00Form)} on
     * {@link CardDemoContext#getToProgram()}) to its web {@link Target} (transaction id +
     * route). The map is immutable and never exposed, so it introduces no shared mutable
     * state. Only the two programs {@code COBIL00C} can hand off to are listed: the sign-on
     * screen (first entry / default) and the main menu (PF3 back).
     */
    private static final Map<String, Target> PROGRAM_ROUTES = Map.of(
            SIGNON_PROGRAM, new Target(SIGNON_TRANID, ROUTE_SIGNON),
            MENU_PROGRAM, DEFAULT_TARGET);

    /**
     * Bill-payment business service (migration of {@code COBIL00C} paragraphs). Owns the
     * account read-update-rewrite, the payment transaction write, and the whole
     * {@code MAIN-PARA} control flow; this controller only orchestrates presentation.
     */
    private final BillPayService billPayService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a scoped
     * proxy and shared with {@link BillPayService}, so both observe the same per-session
     * navigation and selection state.
     */
    private final CardDemoContext context;

    /**
     * Session-scoped confirmation-integrity store (review finding F12). Arms a single-use nonce
     * bound to the account whose balance was displayed when the confirm-payment prompt is rendered,
     * and validates + consumes it on the {@code confirm=Y} turn so a tampered re-post cannot re-aim
     * the payment at a different account or replay it. Web-tier equivalent of the BMS protected
     * confirmation field.
     */
    private final ConfirmationTokenService confirmationTokenService;

    /**
     * Creates the bill-payment controller with its injected collaborators.
     *
     * <p>The constructor only stores the references and invokes no overridable method,
     * so it is free of the {@code this-escape} lint category under the zero-warning
     * build.</p>
     *
     * @param billPayService the bill-payment business service; must not be {@code null}
     * @param context        the session-scoped {@link CardDemoContext} (COMMAREA
     *                       replacement); must not be {@code null}
     * @param confirmationTokenService the session-scoped confirmation-integrity store (review
     *                       finding F12); must not be {@code null}
     */
    public BillPayController(BillPayService billPayService, CardDemoContext context,
                             ConfirmationTokenService confirmationTokenService) {
        this.billPayService = billPayService;
        this.context = context;
        this.confirmationTokenService = confirmationTokenService;
    }

    /**
     * Displays the bill-payment screen - the first-display path of CICS tran {@code CB00}
     * / program {@code COBIL00C}.
     *
     * <p>Reproduces the COBOL {@code MAIN-PARA} entry with no attention key pressed.
     * Delegation is to {@link BillPayService#mainEntry(AidKey, COBIL00Form)} with a
     * {@code null} AID: because {@code mainEntry} owns the {@code EIBCALEN}/
     * {@code CDEMO-PGM-REENTER} state machine, it decides between bouncing to sign-on on a
     * cold entry ({@code EIBCALEN = 0}), performing the first-display initialization
     * (optionally pre-processing the account pre-selected via
     * {@link CardDemoContext#getAcctId()}, matching {@code CDEMO-CB00-TRN-SELECTED}), and,
     * only on a re-entry, evaluating the key. A {@code null} AID is treated as the COBOL
     * {@code WHEN OTHER} default and is never evaluated on the first-display branch, so a
     * fresh display never yields the invalid-key message.</p>
     *
     * @param form the bill-payment screen form, bound under the model attribute
     *             {@code form} so the {@code COBIL00} template can render it
     * @return the logical view name {@link #VIEW_BILLPAY}, or a {@code redirect:} to
     *         sign-on on a cold first entry
     */
    @GetMapping(PATH_BILLPAY)
    public String showBillPay(@ModelAttribute(MODEL_ATTR_FORM) COBIL00Form form,
            HttpSession session) {
        // COBOL MAIN-PARA first display: no AID pressed; the service entry point handles
        // first-entry detection, first-display init, and any pre-selected account.
        return handleInteraction(null, form, session);
    }

    /**
     * Handles a bill-payment submission - the {@code RECEIVE-BILLPAY-SCREEN} +
     * {@code EVALUATE EIBAID} path of CICS tran {@code CB00} / program {@code COBIL00C}.
     *
     * <p>The pressed key is resolved to a {@link PfKey} via {@link #resolvePfKey(String)}
     * and mapped to the service's {@link AidKey} via {@link #toAidKey(PfKey)}, then handed
     * to {@link BillPayService#mainEntry(AidKey, COBIL00Form)}, which reproduces the COBOL
     * {@code EVALUATE EIBAID}: {@code ENTER} runs {@code PROCESS-ENTER-KEY} (validate the
     * account id and confirmation, then, when confirmed, read the account, write the
     * full-balance payment transaction and rewrite the account); {@code PF3} runs
     * {@code RETURN-TO-PREV-SCREEN} (the {@code XCTL} back to the originating program, or
     * the main menu when none is recorded); {@code PF4} runs {@code CLEAR-CURRENT-SCREEN};
     * any other key yields the invalid-key message.</p>
     *
     * @param form  the bound bill-payment form (COBOL {@code COBIL0AI}); supplies the
     *              account id and confirmation, exposed under the model attribute
     *              {@code form}
     * @param pfkey the PF-key token submitted by the template ({@code "ENTER"},
     *              {@code "PF3"} or {@code "PF4"}); a {@code null}/blank/unrecognized value
     *              resolves to the COBOL {@code WHEN OTHER} default
     * @return a {@code redirect:} to the target screen (COBOL {@code XCTL}), or the logical
     *         view name {@link #VIEW_BILLPAY} when the screen is re-displayed
     */
    @PostMapping(PATH_BILLPAY)
    public String submitBillPay(@Valid @ModelAttribute(MODEL_ATTR_FORM) COBIL00Form form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false) String pfkey,
            HttpSession session) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the screen with a neutral banner and no
        // armed token, performing no payment, so the COBOL bill-pay edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            form.setConfirmToken(null);
            return VIEW_BILLPAY;
        }
        AidKey aid = toAidKey(resolvePfKey(pfkey));
        return handleInteraction(aid, form, session);
    }

    /**
     * Delegates to the service entry point and maps its outcome to a redirect or a screen
     * re-display - the controller half of {@code MAIN-PARA}.
     *
     * <p>{@link BillPayService#mainEntry(AidKey, COBIL00Form)} returns a
     * {@link BillPayResult} describing what to do next. A redirect outcome (the COBOL
     * {@code XCTL} of {@code RETURN-TO-PREV-SCREEN}) names the target program; this method
     * looks it up in {@link #PROGRAM_ROUTES} (defaulting to the main menu for an unmapped
     * program, matching the COBOL default), records the target transaction id on the
     * context to preserve the hand-off, and issues the {@code redirect:}. A redisplay
     * outcome re-renders the {@code COBIL00} screen: the fixed header is populated
     * ({@code POPULATE-HEADER-INFO}) and the result's message (empty, the green success
     * line, the neutral confirm prompt, or an error) is placed on the {@code ERRMSG} line
     * ({@code MOVE WS-MESSAGE TO ERRMSGO}); the account-balance/entry fields carried on the
     * form have already been set by the service.</p>
     *
     * @param aid  the resolved attention key, or {@code null} for a plain display (treated
     *             by the service as the COBOL {@code WHEN OTHER} default)
     * @param form the screen form to submit and, on a redisplay, to populate for rendering
     * @return a {@code redirect:} view name for a hand-off, or {@link #VIEW_BILLPAY} for a
     *         redisplay
     */
    private String handleInteraction(AidKey aid, COBIL00Form form, HttpSession session) {
        // Confirmation-integrity gate (finding F12): a Y confirmation commits a full-balance
        // payment, so the committing account must be the server-confirmed target from the prompt
        // turn, bound by a single-use nonce - not a re-posted/forged actidin. A missing arm (a
        // client trying to one-shot the payment without the server having displayed the balance),
        // a swapped account, or a replayed/forged nonce is rejected with no payment.
        if (isConfirmYes(form.getConfirm())) {
            String armedTarget = confirmationTokenService.armedTarget(session, OP_BILLPAY);
            boolean confirmed = armedTarget != null
                    && confirmationTokenService.validate(session, OP_BILLPAY,
                            form.getActidin(), form.getConfirmToken());
            confirmationTokenService.consume(session);
            if (!confirmed) {
                return rejectBillPayConfirmation(aid, form, session, armedTarget);
            }
            // Force the server-confirmed account onto the payment; the re-post cannot re-aim it.
            form.setActidin(armedTarget);
        }
        BillPayResult result = billPayService.mainEntry(aid, form);
        if (result.isRedirect()) {
            // COBOL RETURN-TO-PREV-SCREEN: XCTL to the target program -> redirect to its route.
            Target target = PROGRAM_ROUTES.getOrDefault(result.targetProgram(), DEFAULT_TARGET);
            context.setToTranid(target.transactionId());
            confirmationTokenService.consume(session);
            return REDIRECT_PREFIX + target.route();
        }
        // COBOL SEND-BILLPAY-SCREEN: populate the header and the ERRMSG line, then render.
        populateHeader(form);
        form.setErrmsg(result.message());
        // Finding F12: when the neutral confirm-payment prompt is (re)displayed, arm a single-use
        // nonce bound to the account whose balance is shown; otherwise clear any pending nonce.
        armBillPayConfirmation(result, form, session);
        return VIEW_BILLPAY;
    }

    /**
     * Arms or clears the CB00 payment confirmation nonce after a {@code mainEntry} redisplay
     * (finding F12).
     *
     * <p>The neutral confirm-payment prompt ({@link BillPayService.MessageSeverity#NEUTRAL}) arms a
     * fresh single-use nonce bound to the account whose balance was just displayed
     * ({@code actidin}) and echoes it onto the form's hidden field; any other outcome (a completed
     * payment, an error, or a plain display) clears the pending nonce so it cannot be replayed.</p>
     *
     * @param result  the outcome of the {@code mainEntry} call being rendered
     * @param form    the bill-payment form being redisplayed (receives the echoed nonce)
     * @param session the HTTP session holding the pending confirmation
     */
    private void armBillPayConfirmation(BillPayResult result, COBIL00Form form, HttpSession session) {
        if (result.severity() == BillPayService.MessageSeverity.NEUTRAL
                && form.getActidin() != null && !form.getActidin().isBlank()) {
            form.setConfirmToken(
                    confirmationTokenService.arm(session, OP_BILLPAY, form.getActidin()));
        } else {
            confirmationTokenService.consume(session);
            form.setConfirmToken(null);
        }
    }

    /**
     * Rejects an unvalidated CB00 payment confirmation (finding F12): makes no payment, blanks the
     * confirmation so the service re-reads the account and re-prompts, re-arms a fresh nonce bound
     * to the server-confirmed account, and overlays the integrity banner.
     *
     * <p>When nothing was armed (a one-shot attempt with no prior balance display), the server-
     * confirmed account is unknown, so the posted account is re-prompted - still with no payment -
     * and a nonce is armed so the operator can review the displayed balance and confirm
     * deliberately. When the re-prompt itself routes away (a redirect), that redirect is honored and
     * the pending confirmation is cleared.</p>
     *
     * @param aid         the resolved attention key of the rejected submit
     * @param form        the submitted bill-payment form
     * @param session     the HTTP session holding the pending confirmation
     * @param armedTarget the server-owned account that was armed, or {@code null} if none
     * @return the {@code COBIL00} view with the integrity banner, or a {@code redirect:} view name
     */
    private String rejectBillPayConfirmation(AidKey aid, COBIL00Form form, HttpSession session,
                                             String armedTarget) {
        String target = (armedTarget != null && !armedTarget.isBlank())
                ? armedTarget : form.getActidin();
        form.setActidin(target);
        form.setConfirm(null); // blank confirmation -> service re-reads the account and re-prompts
        BillPayResult result = billPayService.mainEntry(aid, form);
        if (result.isRedirect()) {
            Target redirectTarget = PROGRAM_ROUTES.getOrDefault(result.targetProgram(), DEFAULT_TARGET);
            context.setToTranid(redirectTarget.transactionId());
            confirmationTokenService.consume(session);
            return REDIRECT_PREFIX + redirectTarget.route();
        }
        populateHeader(form);
        // Arm a fresh nonce for the re-prompt, then overlay the integrity banner over the neutral
        // confirm-payment message the service produced (the model holds this same form reference).
        armBillPayConfirmation(result, form, session);
        form.setErrmsg(MSG_CONFIRM_INTEGRITY);
        return VIEW_BILLPAY;
    }

    /**
     * Tests whether the confirmation field carries {@code Y}/{@code y} (COBOL
     * {@code CONFIRM = 'Y' OR 'y'}) - the committing gesture. Surrounding whitespace is ignored.
     *
     * @param confirm the raw confirmation field value (may be {@code null})
     * @return {@code true} when the trimmed value equals {@code Y} ignoring case
     */
    private static boolean isConfirmYes(String confirm) {
        return confirm != null && "Y".equalsIgnoreCase(confirm.trim());
    }

    /**
     * Populates the fixed screen header - migration of {@code POPULATE-HEADER-INFO}
     * ({@code legacy/cbl/COBIL00C.cbl}).
     *
     * <p>Sets the transaction id ({@code WS-TRANID}), program name ({@code WS-PGMNAME}),
     * the two banner titles ({@code CCDA-TITLE01}/{@code CCDA-TITLE02}), and the current
     * date/time rendered with the COBOL {@code MM/DD/YY} and {@code HH:MM:SS} edit masks
     * via {@link DateStruct}. The balance display ({@code CURBAL}) and the account/confirm
     * entry fields are owned by {@link BillPayService} and are not touched here.</p>
     *
     * @param form the form whose header fields are set
     */
    private void populateHeader(COBIL00Form form) {
        form.setTrnname(TRANSACTION_ID);
        form.setPgmname(PROGRAM_NAME);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Restricts request-parameter binding to the fields the {@code COBIL00} screen actually submits
     * (review finding #11) - the account id ({@code actidin}), the confirmation flag
     * ({@code confirm}) and the single-use confirmation token ({@code confirmToken}, finding #12).
     * Display-only header/title/date/balance/message fields are excluded so they can no longer be
     * over-posted; {@code pfkey} arrives as a {@code @RequestParam} and is not bound through the
     * form.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget() is null
        // when @InitBinder runs; the resolved binder.getTargetType() is the reliable discriminator.
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (COBIL00Form.class.equals(targetType)) {
            binder.setAllowedFields("actidin", "confirm", "confirmToken");
        }
    }

    /**
     * Resolves the web PF-key token to the shared {@link PfKey}, routing through
     * {@link PfKeyHandler} exactly as the COBOL {@code EVALUATE EIBAID} did.
     *
     * <p>The {@code COBIL00} Thymeleaf form submits the human-facing labels {@code "ENTER"},
     * {@code "PF3"} and {@code "PF4"} in its {@code pfkey} parameter (mirroring the 3270
     * keyboard), whereas {@link PfKeyHandler#fromAid(String)} recognizes CICS AID mnemonics
     * ({@code DFHENTER}, {@code DFHPFnn}). This helper bridges the two by mapping the label
     * to its AID mnemonic before delegating, so PF-key resolution stays centralized in
     * {@link PfKeyHandler}. A {@code null}, blank or unrecognized token yields
     * {@link PfKey#OTHER}, matching the COBOL {@code WHEN OTHER} branch; the lookup is
     * upper-cased with {@link Locale#ROOT} so it never varies with the default locale.</p>
     *
     * @param pfkey the raw PF-key token from the request (may be {@code null})
     * @return the resolved {@link PfKey}; {@link PfKey#OTHER} for {@code null}, blank or
     *         unrecognized input
     */
    private static PfKey resolvePfKey(String pfkey) {
        if (pfkey == null) {
            return PfKey.OTHER;
        }
        String token = pfkey.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return PfKey.OTHER;
        }
        if (WEB_KEY_ENTER.equals(token)) {
            return PfKeyHandler.fromAid(AID_ENTER);
        }
        if (token.startsWith(WEB_KEY_PF_PREFIX)) {
            return PfKeyHandler.fromAid(AID_PF_PREFIX + token.substring(WEB_KEY_PF_PREFIX.length()));
        }
        return PfKey.OTHER;
    }

    /**
     * Maps a resolved {@link PfKey} to the service's {@link AidKey}, honoring the three keys
     * {@code COBIL00C} acts on and collapsing everything else to the COBOL
     * {@code WHEN OTHER} default.
     *
     * <p>{@link PfKey#ENTER} &rarr; {@link AidKey#ENTER} (process the payment),
     * {@link PfKey#PFK03} &rarr; {@link AidKey#PF3} (back to the previous screen),
     * {@link PfKey#PFK04} &rarr; {@link AidKey#PF4} (clear the screen); any other key -
     * including {@code CLEAR}, PA1/PA2 and PF1/2/5..12 - maps to {@link AidKey#OTHER}, which
     * the service surfaces as the invalid-key message on a re-entry.</p>
     *
     * @param key the resolved PF-key; must not be {@code null}
     * @return the corresponding service {@link AidKey}
     */
    private static AidKey toAidKey(PfKey key) {
        return switch (key) {
            case ENTER -> AidKey.ENTER;
            case PFK03 -> AidKey.PF3;
            case PFK04 -> AidKey.PF4;
            default -> AidKey.OTHER;
        };
    }

    /**
     * Immutable web target for a COBOL {@code XCTL} hand-off: the CICS transaction id to
     * record on the context and the Spring MVC route to redirect to.
     *
     * @param transactionId the target CICS transaction id (for example {@code CM00})
     * @param route         the target web route (for example {@code /menu})
     */
    private record Target(String transactionId, String route) {
    }
}
