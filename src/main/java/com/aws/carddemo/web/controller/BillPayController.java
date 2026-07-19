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
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
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
     * Creates the bill-payment controller with its injected collaborators.
     *
     * <p>The constructor only stores the two references and invokes no overridable method,
     * so it is free of the {@code this-escape} lint category under the zero-warning
     * build.</p>
     *
     * @param billPayService the bill-payment business service; must not be {@code null}
     * @param context        the session-scoped {@link CardDemoContext} (COMMAREA
     *                       replacement); must not be {@code null}
     */
    public BillPayController(BillPayService billPayService, CardDemoContext context) {
        this.billPayService = billPayService;
        this.context = context;
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
    public String showBillPay(@ModelAttribute(MODEL_ATTR_FORM) COBIL00Form form) {
        // COBOL MAIN-PARA first display: no AID pressed; the service entry point handles
        // first-entry detection, first-display init, and any pre-selected account.
        return handleInteraction(null, form);
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
    public String submitBillPay(@ModelAttribute(MODEL_ATTR_FORM) COBIL00Form form,
            @RequestParam(name = PARAM_PFKEY, required = false) String pfkey) {
        AidKey aid = toAidKey(resolvePfKey(pfkey));
        return handleInteraction(aid, form);
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
    private String handleInteraction(AidKey aid, COBIL00Form form) {
        BillPayResult result = billPayService.mainEntry(aid, form);
        if (result.isRedirect()) {
            // COBOL RETURN-TO-PREV-SCREEN: XCTL to the target program -> redirect to its route.
            Target target = PROGRAM_ROUTES.getOrDefault(result.targetProgram(), DEFAULT_TARGET);
            context.setToTranid(target.transactionId());
            return REDIRECT_PREFIX + target.route();
        }
        // COBOL SEND-BILLPAY-SCREEN: populate the header and the ERRMSG line, then render.
        populateHeader(form);
        form.setErrmsg(result.message());
        return VIEW_BILLPAY;
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
