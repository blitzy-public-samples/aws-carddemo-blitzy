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
import com.aws.carddemo.dto.screen.COACTUPForm;
import com.aws.carddemo.dto.screen.COACTVWForm;
import com.aws.carddemo.service.online.AccountUpdateService;
import com.aws.carddemo.service.online.AccountUpdateService.AccountUpdateResult;
import com.aws.carddemo.service.online.AccountUpdateService.AccountUpdateState;
import com.aws.carddemo.service.online.AccountUpdateService.ChangeAction;
import com.aws.carddemo.service.online.AccountUpdateService.ScreenOutcome;
import com.aws.carddemo.service.online.AccountViewService;
import com.aws.carddemo.service.online.AccountViewService.AccountViewResult;
import com.aws.carddemo.service.online.AccountViewService.RoutingAction;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.ScreenTitles;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the AWS CardDemo account view and account update
 * screens - the web-tier replacement for two CICS account transactions.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COACTVWC.cbl} (CICS tran CAVW, mapset
 * COACTVW, map CACTVWA) - the read-only account inquiry; and
 * {@code legacy/cbl/COACTUPC.cbl} (CICS tran CAUP, mapset COACTUP, map CACTUPA) -
 * the read-update-rewrite account maintenance. Both transaction-to-program
 * bindings are verified in {@code legacy/csd/CARDDEMO.CSD}
 * ({@code DEFINE TRANSACTION(CAVW) ... PROGRAM(COACTVWC)} and
 * {@code DEFINE TRANSACTION(CAUP) ... PROGRAM(COACTUPC)}).</p>
 *
 * <h2>Presentation-only responsibility</h2>
 * <p>This controller performs presentation and navigation only. All business
 * logic lives in the service tier: the three-file read chain
 * ({@code CXACAIX} cross-reference &rarr; {@code ACCTDAT} account &rarr;
 * {@code CUSTDAT} customer) in {@link AccountViewService}, and the
 * {@code @Transactional} read-update-rewrite with the optimistic-lock confirm
 * state machine (the ~78 paragraphs of {@code COACTUPC}) in
 * {@link AccountUpdateService}. No repository access and no arithmetic are
 * performed here; the controller resolves the pressed PF-key, calls the correct
 * service method, and either renders the Thymeleaf screen or issues a redirect.
 * The COBOL presentation paragraphs map as follows:</p>
 * <ul>
 *   <li>{@code SEND-MAP} / {@code POPULATE-HEADER-INFO} / {@code 3250-SETUP-INFOMSG}
 *       &rarr; {@link #populateViewHeader(COACTVWForm)} /
 *       {@link #populateUpdateHeader(COACTUPForm)} plus the info-line resolution in
 *       {@link #infoForAccountUpdate(ChangeAction)}.</li>
 *   <li>{@code RECEIVE-MAP} &rarr; Spring form binding of {@link COACTVWForm} /
 *       {@link COACTUPForm} on the {@code POST} handlers.</li>
 *   <li>{@code EVALUATE EIBAID} / {@code YYYY-STORE-PFKEY} &rarr;
 *       {@link #resolvePfKey(String)}; the account/card programs coerce any invalid
 *       key to ENTER inside the service, so no invalid-key banner is rendered
 *       here.</li>
 * </ul>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The CICS {@code COMMAREA} ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}; first entry ({@code EIBCALEN = 0}) is
 * {@link CardDemoContext#isNew()}. The {@code CDEMO-PGM-CONTEXT} enter/re-enter flag
 * is preserved through {@link CardDemoContext#markEnter()} /
 * {@link CardDemoContext#markReenter()}. The {@code GET} handlers render the
 * first-entry screen and flip the context to re-enter so the following {@code POST}
 * is processed as a re-entry (the account id is edited/read rather than the empty
 * search screen being re-shown). For CAVW the controller owns this flip
 * ({@link AccountViewService#mainEntry} only reads the flag); for CAUP the flip is
 * owned by {@link AccountUpdateService#process} and the controller only supplies and
 * persists the program-private {@link AccountUpdateState} across turns via the HTTP
 * session. The selected {@code acctId} flows through {@link CardDemoContext#getAcctId()}
 * (set by a caller such as the account list, or entered on the screen).</p>
 *
 * <h2>Transaction &rarr; program / route mapping</h2>
 * <table border="1">
 *   <caption>Account screens: tran id, program, view, and web routes</caption>
 *   <tr><th>Tran</th><th>Program</th><th>View</th><th>GET / POST route</th></tr>
 *   <tr><td>CAVW</td><td>COACTVWC</td><td>COACTVW</td><td>/account/view</td></tr>
 *   <tr><td>CAUP</td><td>COACTUPC</td><td>COACTUP</td><td>/account/update</td></tr>
 * </table>
 *
 * <p>Constructor dependency injection, {@code private final} collaborators, no
 * Lombok, no field injection; a {@link Controller} (never {@code @RestController})
 * returning logical Thymeleaf view names, so no REST/JSON surface is introduced
 * (AAP &sect;0.3.4). The five monetary fields (credit limit, cash limit, current
 * balance, current-cycle credit/debit) are carried on the forms as already-edited
 * {@code String}s; this layer never parses them and never introduces
 * {@code float}/{@code double} (AAP &sect;0.6.1). Typed exceptions raised by the
 * service tier ({@code RecordNotFoundException}, {@code LogicError}, and the
 * {@code DataAccessException} optimistic-lock translations) are deliberately not
 * caught here; they propagate to {@code exception.GlobalExceptionHandler}
 * (AAP &sect;0.6.5). Compiles warning-free under {@code --release 25}.</p>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 * @see CardDemoContext
 * @see COACTVWForm
 * @see COACTUPForm
 */
@Controller
public class AccountController {

    /** Logical Thymeleaf view name for CAVW; resolves to {@code templates/COACTVW.html}. */
    private static final String VIEW_ACCOUNT_VIEW = "COACTVW";

    /** Logical Thymeleaf view name for CAUP; resolves to {@code templates/COACTUP.html}. */
    private static final String VIEW_ACCOUNT_UPDATE = "COACTUP";

    /**
     * Neutral banner shown when data binding rejects a field for exceeding its declared
     * {@code @Size} width (review finding #11). Only reachable by a crafted client (the template
     * {@code maxlength} / 3270 field width makes it impossible otherwise), so it carries no COBOL
     * business message and simply re-displays the screen without performing any account work.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    /** Model attribute name bound by the COACTVW / COACTUP templates ({@code th:object="${form}"}). */
    private static final String MODEL_ATTR_FORM = "form";

    /**
     * Model attribute driving the COACTUP {@code th:readonly} guards (review finding
     * #10). {@code true} only in the "awaiting PF5 confirm" state, where every
     * editable field is rendered read-only so the confirmation screen presents - and
     * commits - exactly the validated values.
     */
    private static final String MODEL_ATTR_CONFIRM_MODE = "confirmMode";

    /** GET/POST route for the account-view screen (CICS tran CAVW). */
    private static final String ROUTE_ACCOUNT_VIEW = "/account/view";

    /** GET/POST route for the account-update screen (CICS tran CAUP). */
    private static final String ROUTE_ACCOUNT_UPDATE = "/account/update";

    /** COBOL {@code LIT-THISTRANID VALUE 'CAVW'} - the account-view transaction id. */
    private static final String TRAN_VIEW = "CAVW";

    /** COBOL {@code LIT-THISTRANID VALUE 'CAUP'} - the account-update transaction id. */
    private static final String TRAN_UPDATE = "CAUP";

    /** Spring MVC redirect prefix used to mirror the COBOL {@code XCTL PROGRAM(...)}. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Request-parameter name carrying the pressed PF-key from the account templates. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Web PF-key token for the ENTER action submitted by the templates. */
    private static final String ENTER_TOKEN = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code PF3}). */
    private static final String PF_TOKEN_PREFIX = "PF";

    /** CICS AID mnemonic for ENTER, consumed by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix for program-function keys ({@code DFHPFnn}). */
    private static final String AID_PF_PREFIX = "DFHPF";

    /** HTTP session attribute key holding the CAUP program-private {@link AccountUpdateState}. */
    private static final String SESSION_ATTR_UPDATE_STATE = "accountUpdateState";

    /** Main-menu program ({@code COMEN01C}); the default PF3 return target for both screens. */
    private static final String PGM_MENU = "COMEN01C";

    /** Sign-on program ({@code COSGN00C}). */
    private static final String PGM_SIGNON = "COSGN00C";

    /** Administration-menu program ({@code COADM01C}). */
    private static final String PGM_ADMIN_MENU = "COADM01C";

    /** Account-view program ({@code COACTVWC}); this screen's own program (tran CAVW). */
    private static final String PGM_ACCOUNT_VIEW = "COACTVWC";

    /** Account-update program ({@code COACTUPC}); this screen's own program (tran CAUP). */
    private static final String PGM_ACCOUNT_UPDATE = "COACTUPC";

    /** Card-list program ({@code COCRDLIC}). */
    private static final String PGM_CARD_LIST = "COCRDLIC";

    /** Card-detail program ({@code COCRDSLC}). */
    private static final String PGM_CARD_DETAIL = "COCRDSLC";

    /** Card-update program ({@code COCRDUPC}). */
    private static final String PGM_CARD_UPDATE = "COCRDUPC";

    /** Main-menu route ({@code XCTL COMEN01C}). */
    private static final String ROUTE_MENU = "/menu";

    /** Sign-on route ({@code XCTL COSGN00C}). */
    private static final String ROUTE_SIGNON = "/signon";

    /** Administration-menu route ({@code XCTL COADM01C}). */
    private static final String ROUTE_ADMIN_MENU = "/admin/menu";

    /** Card-list route ({@code XCTL COCRDLIC}). */
    private static final String ROUTE_CARD_LIST = "/card/list";

    /** Card-detail route ({@code XCTL COCRDSLC}). */
    private static final String ROUTE_CARD_DETAIL = "/card/detail";

    /** Card-update route ({@code XCTL COCRDUPC}). */
    private static final String ROUTE_CARD_UPDATE = "/card/update";

    /**
     * CAVW information line ({@code COACTVWC} 88 {@code PROMPT-FOR-INPUT}, COBOL line
     * 114). {@link AccountViewService#mainEntry} always returns this same text, so the
     * first-entry {@code GET} render uses this constant to keep the line identical.
     */
    private static final String INFO_VIEW_PROMPT = "Enter or update id of account to display";

    /** CAUP {@code PROMPT-FOR-SEARCH-KEYS} (COBOL line 469) - not-fetched / first-entry info line. */
    private static final String INFO_UPDATE_PROMPT = "Enter or update id of account to update";

    /** CAUP {@code PROMPT-FOR-CHANGES} (COBOL line 471) - details shown / edit-error info line. */
    private static final String INFO_UPDATE_CHANGES = "Update account details presented above.";

    /** CAUP {@code PROMPT-FOR-CONFIRMATION} (COBOL line 473) - edits validated, awaiting PF5. */
    private static final String INFO_UPDATE_CONFIRM = "Changes validated.Press F5 to save";

    /** CAUP {@code CONFIRM-UPDATE-SUCCESS} (COBOL line 475) - update committed. */
    private static final String INFO_UPDATE_SUCCESS = "Changes committed to database";

    /** CAUP {@code INFORM-FAILURE} (COBOL line 477) - lock error / rewrite failure. */
    private static final String INFO_UPDATE_FAILURE = "Changes unsuccessful. Please try again";

    /**
     * Read-only account inquiry business service (migration of {@code COACTVWC}
     * paragraphs). Owns the {@code CXACAIX}&rarr;{@code ACCTDAT}&rarr;{@code CUSTDAT}
     * read chain, the field edits, and the PF3 hand-off; this controller only renders
     * its result or issues the redirect.
     */
    private final AccountViewService accountViewService;

    /**
     * Read-update-rewrite account maintenance business service (migration of
     * {@code COACTUPC}). Owns the field edits, the confirm/optimistic-lock state
     * machine, and the {@code @Transactional} write; this controller only routes the
     * resolved PF-key to it and renders the resulting form / messages.
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a
     * scoped proxy and shared with both services, so all three observe the same
     * per-session navigation and identity state.
     */
    private final CardDemoContext context;

    /**
     * Creates the account controller with its injected collaborators.
     *
     * <p>The constructor only stores the three references and invokes no overridable
     * method, so it is free of the {@code this-escape} lint category under the
     * zero-warning build.</p>
     *
     * @param accountViewService   the account-view business service; must not be {@code null}
     * @param accountUpdateService the account-update business service; must not be {@code null}
     * @param context              the session-scoped {@link CardDemoContext}
     *                             (COMMAREA replacement); must not be {@code null}
     */
    public AccountController(AccountViewService accountViewService,
            AccountUpdateService accountUpdateService,
            CardDemoContext context) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
        this.context = context;
    }

    /**
     * Displays the account-view screen - the first-entry {@code SEND-MAP} path of
     * CICS tran {@code CAVW} / program {@code COACTVWC}.
     *
     * <p>Renders the empty prompt screen: it populates the fixed header
     * ({@code POPULATE-HEADER-INFO}), prefills the account-id field from
     * {@link CardDemoContext#getAcctId()} when a caller has selected an account, and
     * shows the {@link #INFO_VIEW_PROMPT} information line. The context is then flipped
     * to re-enter ({@link CardDemoContext#markReenter()}) and marked initialized, so
     * the following {@code POST} is processed as a re-entry by
     * {@link AccountViewService#mainEntry} (the account id is edited and read rather
     * than the prompt being re-shown).</p>
     *
     * @param form the account-view form, bound under the model attribute {@code form}
     *             so the {@code COACTVW} template can render it
     * @return the logical view name {@link #VIEW_ACCOUNT_VIEW}
     */
    @GetMapping(ROUTE_ACCOUNT_VIEW)
    public String showAccountView(@ModelAttribute(MODEL_ATTR_FORM) COACTVWForm form) {
        populateViewHeader(form);
        // Finding P5-03: on first entry the COBOL blanks ACCTSIDO (WHEN CDEMO-PGM-ENTER); it never
        // prefills the persistent CDEMO-ACCT-ID. The port had prefilled from the SHARED session
        // account id, leaking a stale account (e.g. "00000000003", or "00000000000" when zero)
        // selected on an unrelated screen into the search field. Leave the field blank; the operator
        // supplies the account id, which the POST re-entry echoes as the screen-local CC-ACCT-ID.
        form.setAcctsid("");
        form.setInfomsg(INFO_VIEW_PROMPT);
        form.setErrmsg("");
        // CDEMO-PGM-CONTEXT: flip to re-enter so the POST edits/reads the account id.
        context.markReenter();
        context.markInitialized();
        return VIEW_ACCOUNT_VIEW;
    }

    /**
     * Handles an account-view submission - the {@code RECEIVE-MAP} +
     * {@code EVALUATE EIBAID} path of CICS tran {@code CAVW} / program
     * {@code COACTVWC}.
     *
     * <p>The pressed key is resolved to a {@link PfKey} and passed to
     * {@link AccountViewService#mainEntry(COACTVWForm, PfKey)}, which mirrors the
     * COBOL {@code 0000-MAIN}: ENTER (and any coerced invalid key) walks the
     * {@code 2000-PROCESS-INPUTS} edits and the {@code 9000-READ-ACCT} chain
     * ({@code getCardXrefByAccount} / {@code getAcctDataByAccount} /
     * {@code getCustDataByCust}) and displays the account plus customer fields; PF3
     * ({@code WHEN CCARD-AID-PFK03}) records the return target and yields
     * {@link RoutingAction#REDIRECT}. A not-found record surfaces as
     * {@code RecordNotFoundException} from the service and is intentionally not caught
     * here - it propagates to the global handler.</p>
     *
     * @param form  the bound account-view form (COBOL {@code CACTVWAI}); its
     *              {@code acctsid} carries the account-id filter
     * @param pfkey the PF-key token submitted by the template ({@code "ENTER"} or
     *              {@code "PF3"}); absent submissions default to {@code ENTER}
     * @return a {@code redirect:} to the caller / main menu on PF3, otherwise the
     *         logical view name {@link #VIEW_ACCOUNT_VIEW} with the account displayed
     */
    @PostMapping(ROUTE_ACCOUNT_VIEW)
    public String handleAccountView(@Valid @ModelAttribute(MODEL_ATTR_FORM) COACTVWForm form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = ENTER_TOKEN) String pfkey) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the screen with a neutral banner and
        // performs no lookup, so the COBOL account-view edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateViewHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            return VIEW_ACCOUNT_VIEW;
        }
        PfKey key = resolvePfKey(pfkey);
        AccountViewResult result = accountViewService.mainEntry(form, key);
        if (result.action() == RoutingAction.REDIRECT) {
            // COBOL XCTL PROGRAM(CDEMO-TO-PROGRAM): the service set the target; redirect.
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        // WHEN CDEMO-PGM-REENTER SEND-MAP: header + the account/customer fields the
        // service populated on the form, the red return message, and the info prompt.
        populateViewHeader(form);
        form.setInfomsg(result.infoMessage());
        form.setErrmsg(result.returnMessage());
        return VIEW_ACCOUNT_VIEW;
    }

    /**
     * Displays the account-update screen - the first-entry {@code SEND-MAP} path of
     * CICS tran {@code CAUP} / program {@code COACTUPC}.
     *
     * <p>Establishes a fresh program-private {@link AccountUpdateState} (COBOL
     * {@code WS-THIS-PROGCOMMAREA} with {@code ACUP-DETAILS-NOT-FETCHED}) in the HTTP
     * session, populates the header, prefills the account-id field from
     * {@link CardDemoContext#getAcctId()} when present, and shows the
     * {@code PROMPT-FOR-SEARCH-KEYS} information line resolved from the not-fetched
     * change action ({@link #infoForAccountUpdate(ChangeAction)}). The context is
     * flipped to re-enter and marked initialized so the following {@code POST} is
     * processed rather than reset to the empty search screen.</p>
     *
     * @param form    the account-update form, bound under the model attribute
     *                {@code form} so the {@code COACTUP} template can render it
     * @param session the HTTP session that carries the {@link AccountUpdateState}
     *                across pseudo-conversational turns
     * @return the logical view name {@link #VIEW_ACCOUNT_UPDATE}
     */
    @GetMapping(ROUTE_ACCOUNT_UPDATE)
    public String showAccountUpdate(@ModelAttribute(MODEL_ATTR_FORM) COACTUPForm form,
            HttpSession session, Model model) {
        AccountUpdateState state = new AccountUpdateState();
        session.setAttribute(SESSION_ATTR_UPDATE_STATE, state);
        populateUpdateHeader(form);
        // Finding P5-03: on first entry the COBOL blanks ACCTSIDO (WHEN CDEMO-PGM-ENTER) and never
        // prefills the persistent CDEMO-ACCT-ID; the port had leaked the SHARED session account id
        // into the search field. Leave it blank - the operator types the account id, which the POST
        // re-entry echoes as the screen-local CC-ACCT-ID work area.
        form.setAcctsid("");
        form.setInfomsg(infoForAccountUpdate(state.getChangeAction()));
        form.setErrmsg("");
        // Review finding #10: a fresh not-fetched screen is never in the confirm
        // state, so fields are editable and no confirmation token is issued.
        model.addAttribute(MODEL_ATTR_CONFIRM_MODE, Boolean.FALSE);
        form.setConfirmToken(state.getConfirmToken());
        // CDEMO-PGM-CONTEXT: flip to re-enter; the service owns further flips on POST.
        context.markReenter();
        context.markInitialized();
        return VIEW_ACCOUNT_UPDATE;
    }

    /**
     * Handles an account-update submission - the {@code RECEIVE-MAP} +
     * {@code 0000-MAIN} state machine of CICS tran {@code CAUP} / program
     * {@code COACTUPC}.
     *
     * <p>The pressed key is resolved to a {@link PfKey} and, together with the
     * session-held {@link AccountUpdateState}, passed to the single
     * {@code @Transactional} entry point
     * {@link AccountUpdateService#process(COACTUPForm, PfKey, AccountUpdateState)},
     * which dispatches every key internally: ENTER runs the {@code 1200-1280} field
     * edits and validation; PF5 runs the {@code 9600-WRITE-PROCESSING} confirm/save
     * with the {@code 9700-CHECK-CHANGE-IN-REC} optimistic-lock check (honored only in
     * the {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED} state); PF12 re-fetches the
     * original record (discarding edits); PF3 records the return target and yields
     * {@link ScreenOutcome#EXIT_TO_CALLER}; any invalid key is coerced to ENTER. The
     * service owns the confirm state machine and the transactional write; this
     * controller only persists the (mutated) state back to the session, renders the
     * resulting form with its red return message and the green info line resolved from
     * the returned change action, or issues the redirect. Optimistic-lock /
     * data-access translations propagate to the global handler.</p>
     *
     * @param form    the bound account-update form (COBOL {@code CACTUPAI})
     * @param pfkey   the PF-key token ({@code "ENTER"}, {@code "PF3"}, {@code "PF5"},
     *                or {@code "PF12"}); absent submissions default to {@code ENTER}
     * @param session the HTTP session carrying the {@link AccountUpdateState}
     * @return a {@code redirect:} to the caller / main menu on PF3, otherwise the
     *         logical view name {@link #VIEW_ACCOUNT_UPDATE}
     */
    @PostMapping(ROUTE_ACCOUNT_UPDATE)
    public String handleAccountUpdate(@Valid @ModelAttribute(MODEL_ATTR_FORM) COACTUPForm form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = ENTER_TOKEN) String pfkey,
            HttpSession session, Model model) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the screen (not confirm mode, no armed
        // token) with a neutral banner and performs no update, so the COBOL account-update edit
        // ordering owned by AccountUpdateService is untouched.
        if (bindingResult.hasErrors()) {
            populateUpdateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            model.addAttribute(MODEL_ATTR_CONFIRM_MODE, false);
            form.setConfirmToken(null);
            return VIEW_ACCOUNT_UPDATE;
        }
        PfKey key = resolvePfKey(pfkey);
        AccountUpdateState state = resolveUpdateState(session);
        AccountUpdateResult result = accountUpdateService.process(form, key, state);
        if (result.outcome() == ScreenOutcome.EXIT_TO_CALLER) {
            // COBOL SYNCPOINT + XCTL PROGRAM(CDEMO-TO-PROGRAM): end this conversation.
            session.removeAttribute(SESSION_ATTR_UPDATE_STATE);
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        // SEND-MAP: persist the state mutated in place by process(), then render the
        // header, the red WS-RETURN-MSG line, and the green WS-INFO-MSG line derived
        // from the resulting ACUP-CHANGE-ACTION (COBOL 3250-SETUP-INFOMSG).
        session.setAttribute(SESSION_ATTR_UPDATE_STATE, state);
        populateUpdateHeader(form);
        form.setInfomsg(infoForAccountUpdate(result.changeAction()));
        form.setErrmsg(result.message());
        // Review finding #10: when the resulting action is "awaiting PF5 confirm" the
        // editable fields are made read-only and the freshly issued single-use token
        // is echoed to the hidden field; every other state clears both. The token is
        // read from the session state that process() mutated in place.
        model.addAttribute(MODEL_ATTR_CONFIRM_MODE,
                result.changeAction() == ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        form.setConfirmToken(state.getConfirmToken());
        return VIEW_ACCOUNT_UPDATE;
    }

    /**
     * Populates the fixed CAVW screen header - migration of the account-view
     * {@code POPULATE-HEADER-INFO}.
     *
     * <p>Sets the transaction id, program name, the two banner titles, and the
     * current date/time rendered with the COBOL {@code MM/DD/YY} and {@code HH:MM:SS}
     * edit masks via {@link DateStruct}.</p>
     *
     * @param form the account-view form whose header fields are set
     */
    private void populateViewHeader(COACTVWForm form) {
        form.setTrnname(TRAN_VIEW);
        form.setPgmname(PGM_ACCOUNT_VIEW);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the fixed CAUP screen header - migration of the account-update
     * {@code 3100-SCREEN-INIT} header moves ({@code COACTUPC} lines 2925-2953).
     *
     * <p>Sets the transaction id, program name, the two banner titles, and the
     * current date/time rendered with the COBOL {@code MM/DD/YY} and {@code HH:MM:SS}
     * edit masks via {@link DateStruct}.</p>
     *
     * @param form the account-update form whose header fields are set
     */
    private void populateUpdateHeader(COACTUPForm form) {
        form.setTrnname(TRAN_UPDATE);
        form.setPgmname(PGM_ACCOUNT_UPDATE);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Restricts request-parameter binding to the fields each account screen actually submits
     * (review finding #11), switching on the bound form type since both screens share the
     * {@code form} model attribute. The account-view screen submits only the search key
     * ({@code acctsid}); the account-update screen submits its editable detail fields plus the
     * single-use confirmation token ({@code confirmToken}, finding #10). Display-only header/title/
     * date/message fields and the read-only balance are excluded, so they can no longer be
     * over-posted; {@code pfkey} arrives as a {@code @RequestParam} and is not bound through the
     * form.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // NOTE: Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget()
        // is null when @InitBinder runs; the resolved binder.getTargetType() is the reliable
        // discriminator here (it is null for simple @RequestParam binders such as pfkey).
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (COACTVWForm.class.equals(targetType)) {
            binder.setAllowedFields("acctsid");
        } else if (COACTUPForm.class.equals(targetType)) {
            binder.setAllowedFields(
                    "acctsid", "acsttus", "acurbal", "acrcycr", "acrcydb",
                    "opnyear", "opnmon", "opnday", "expyear", "expmon", "expday",
                    "risyear", "rismon", "risday", "acrdlim", "acshlim", "aaddgrp",
                    "actssn1", "actssn2", "actssn3", "acstfco", "acstnum",
                    "dobyear", "dobmon", "dobday",
                    "acsfnam", "acsmnam", "acslnam",
                    "acsadl1", "acsadl2", "acscity", "acsstte", "acszipc", "acsctry",
                    "acsph1a", "acsph1b", "acsph1c", "acsph2a", "acsph2b", "acsph2c",
                    "acsgovt", "acseftc", "acspflg", "confirmToken");
        }
    }

    /**
     * Resolves the CAUP green information line from the change action - migration of
     * paragraph {@code 3250-SETUP-INFOMSG} ({@code COACTUPC} lines 2955-2982).
     *
     * <p>The COBOL {@code EVALUATE TRUE} checks {@code WHEN CDEMO-PGM-ENTER} first, but
     * that branch sets {@code PROMPT-FOR-SEARCH-KEYS} - the same text as
     * {@code WHEN ACUP-DETAILS-NOT-FETCHED} - and on the normal display path the
     * program context is re-enter, so mapping purely on the change action reproduces
     * the identical line for every state. The {@code WS-RETURN-MSG} red banner is
     * carried separately on {@link AccountUpdateResult#message()}.</p>
     *
     * @param action the resulting {@code ACUP-CHANGE-ACTION}; never {@code null}
     * @return the exact COBOL information-line text for that change action
     */
    private static String infoForAccountUpdate(ChangeAction action) {
        return switch (action) {
            case DETAILS_NOT_FETCHED -> INFO_UPDATE_PROMPT;
            case SHOW_DETAILS, CHANGES_NOT_OK -> INFO_UPDATE_CHANGES;
            case CHANGES_OK_NOT_CONFIRMED -> INFO_UPDATE_CONFIRM;
            case CHANGES_OKAYED_AND_DONE -> INFO_UPDATE_SUCCESS;
            case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> INFO_UPDATE_FAILURE;
        };
    }

    /**
     * Returns the CAUP program-private state from the session, creating a fresh
     * not-fetched state when none is present.
     *
     * <p>Mirrors the COBOL {@code WS-THIS-PROGCOMMAREA} that survives each
     * pseudo-conversational {@code EXEC CICS RETURN}; a {@code POST} arriving without a
     * prior {@code GET} (or after the state was cleared on an exit) starts from a fresh
     * {@link AccountUpdateState}, which {@link AccountUpdateService#process} treats as
     * first entry.</p>
     *
     * @param session the current HTTP session
     * @return the existing or a freshly created {@link AccountUpdateState}
     */
    private static AccountUpdateState resolveUpdateState(HttpSession session) {
        Object attribute = session.getAttribute(SESSION_ATTR_UPDATE_STATE);
        if (attribute instanceof AccountUpdateState state) {
            return state;
        }
        AccountUpdateState fresh = new AccountUpdateState();
        session.setAttribute(SESSION_ATTR_UPDATE_STATE, fresh);
        return fresh;
    }

    /**
     * Resolves the web PF-key token to the shared {@link PfKey}, routing through
     * {@link PfKeyHandler} exactly as the COBOL {@code EVALUATE EIBAID} /
     * {@code YYYY-STORE-PFKEY} did.
     *
     * <p>The {@code COACTVW} / {@code COACTUP} templates submit the human-facing labels
     * {@code "ENTER"}, {@code "PF3"}, {@code "PF5"}, and {@code "PF12"} in the
     * {@code pfkey} parameter, whereas {@link PfKeyHandler#fromAid(String)} recognizes
     * CICS AID mnemonics ({@code DFHENTER}, {@code DFHPFnn}). This helper bridges the
     * two by mapping the label to its AID mnemonic before delegating. A {@code null},
     * blank, or unrecognized token yields {@link PfKey#OTHER}; the account/card
     * programs then coerce {@code OTHER} to ENTER inside the service
     * ({@code IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE}), so no invalid-key banner is
     * rendered by this controller. The lookup is upper-cased with {@link Locale#ROOT}
     * so it never varies with the default locale.</p>
     *
     * @param pfkey the raw PF-key token from the request (may be {@code null})
     * @return the resolved {@link PfKey}; {@link PfKey#OTHER} for {@code null}, blank,
     *         or unrecognized input
     */
    private static PfKey resolvePfKey(String pfkey) {
        if (pfkey == null) {
            return PfKey.OTHER;
        }
        String token = pfkey.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return PfKey.OTHER;
        }
        if (ENTER_TOKEN.equals(token)) {
            return PfKeyHandler.fromAid(AID_ENTER);
        }
        if (token.startsWith(PF_TOKEN_PREFIX)) {
            return PfKeyHandler.fromAid(AID_PF_PREFIX + token.substring(PF_TOKEN_PREFIX.length()));
        }
        return PfKey.OTHER;
    }

    /**
     * Maps a target program name to its web route, reproducing the COBOL
     * {@code XCTL PROGRAM(...)} destination as a Spring MVC redirect path.
     *
     * <p>The target program is the one the service recorded on
     * {@link CardDemoContext#getToProgram()} for the PF3 return - the caller when
     * known, otherwise the main-menu default ({@code COMEN01C}). An unmapped or
     * {@code null} program degrades safely to the main menu rather than throwing.</p>
     *
     * @param programName the COBOL target program name (for example {@code COMEN01C})
     * @return the redirect route for the program, or {@link #ROUTE_MENU} when unmapped
     */
    private static String routeForProgram(String programName) {
        if (programName == null) {
            return ROUTE_MENU;
        }
        return switch (programName.trim().toUpperCase(Locale.ROOT)) {
            case PGM_MENU -> ROUTE_MENU;
            case PGM_SIGNON -> ROUTE_SIGNON;
            case PGM_ADMIN_MENU -> ROUTE_ADMIN_MENU;
            case PGM_ACCOUNT_VIEW -> ROUTE_ACCOUNT_VIEW;
            case PGM_ACCOUNT_UPDATE -> ROUTE_ACCOUNT_UPDATE;
            case PGM_CARD_LIST -> ROUTE_CARD_LIST;
            case PGM_CARD_DETAIL -> ROUTE_CARD_DETAIL;
            case PGM_CARD_UPDATE -> ROUTE_CARD_UPDATE;
            default -> ROUTE_MENU;
        };
    }
}
