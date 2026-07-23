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
import com.aws.carddemo.dto.screen.COTRN00Form;
import com.aws.carddemo.dto.screen.COTRN01Form;
import com.aws.carddemo.dto.screen.COTRN02Form;
import com.aws.carddemo.service.online.TransactionAddService;
import com.aws.carddemo.service.online.TransactionAddService.ScreenAction;
import com.aws.carddemo.service.online.TransactionAddService.TransactionAddResult;
import com.aws.carddemo.service.online.TransactionListService;
import com.aws.carddemo.service.online.TransactionListService.TransactionListResult;
import com.aws.carddemo.service.online.TransactionViewService;
import com.aws.carddemo.service.online.TransactionViewService.TransactionViewResult;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.ScreenTitles;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the AWS CardDemo transaction browse / view / add screens.
 *
 * <p>This one controller is the web-tier replacement for <b>three</b> CICS
 * pseudo-conversational transactions, each verified in {@code legacy/csd/CARDDEMO.CSD}:</p>
 * <ul>
 *   <li><b>CT00 (list)</b> &rarr; {@code legacy/cbl/COTRN00C.cbl} (mapset {@code COTRN00}, map
 *       {@code COTRN0A}); view {@code COTRN00}. {@code DEFINE TRANSACTION(CT00) ... PROGRAM(COTRN00C)}.</li>
 *   <li><b>CT01 (view)</b> &rarr; {@code legacy/cbl/COTRN01C.cbl} (mapset {@code COTRN01}, map
 *       {@code COTRN1A}); view {@code COTRN01}. {@code DEFINE TRANSACTION(CT01) ... PROGRAM(COTRN01C)}.</li>
 *   <li><b>CT02 (add)</b> &rarr; {@code legacy/cbl/COTRN02C.cbl} (mapset {@code COTRN02}, map
 *       {@code COTRN2A}); view {@code COTRN02}. {@code DEFINE TRANSACTION(CT02) ... PROGRAM(COTRN02C)}.</li>
 * </ul>
 *
 * <h2>Presentation + navigation only (AAP &sect;0.3.3, &sect;0.3.4)</h2>
 * <p>This class performs HTTP-to-service orchestration only: it resolves the pressed PF-key,
 * delegates to the matching business service, populates the fixed screen header, and either
 * re-renders the Thymeleaf view or issues the {@code redirect:} that mirrors the COBOL
 * {@code EXEC CICS XCTL}. All business logic &mdash; paging, the ten-row page-size and
 * {@code 'S'}-selection contract, transaction lookup, field validation, and the transactional
 * add &mdash; lives in {@link TransactionListService}, {@link TransactionViewService}, and
 * {@link TransactionAddService} (each the migration of the numbered paragraphs of its COBOL
 * program). No repository access, and no monetary arithmetic, is performed here; transaction
 * amounts remain edit-masked {@link String}s on the forms and the parity-critical
 * {@code BigDecimal} handling stays in the service tier (AAP &sect;0.6.1).</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The CICS {@code COMMAREA} ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}. Each service {@code mainEntry} reproduces the COBOL {@code MAIN-PARA}
 * state machine: first entry ({@code EIBCALEN = 0}) bounces to sign-on; the first display of a
 * transaction ({@code NOT CDEMO-PGM-REENTER}) runs its enter-key path; and subsequent submissions
 * dispatch on the attention id. Because every redirect path records the hand-off target on
 * {@link CardDemoContext#getToProgram()} (defaulting to {@code COSGN00C} when unset), this
 * controller routes every redirect through that single field via {@link #routeForProgram(String)}.</p>
 *
 * <h2>Selection hand-off (CT00 &rarr; CT01)</h2>
 * <p>The COBOL {@code CDEMO-CT00-TRN-SELECTED} field &mdash; the transaction id chosen with an
 * {@code 'S'} on the list &mdash; is not part of the shared {@code COCOM01Y}; the transaction-view
 * program receives it as an explicit parameter. Across the {@code POST}&rarr;redirect&rarr;{@code GET}
 * boundary that realizes the {@code XCTL}, this controller carries the selected id in a one-shot
 * Spring flash attribute ({@value #FLASH_SELECTED_TRAN_ID}) and supplies it to
 * {@link TransactionViewService#mainEntry(COTRN01Form, TransactionViewService.AidKey, String)} on
 * arrival, exactly reproducing the {@code CDEMO-CT00-TRN-SELECTED} &rarr; {@code CDEMO-CT01-TRN-SELECTED}
 * move.</p>
 *
 * <h2>Exception handling (AAP &sect;0.6.5)</h2>
 * <p>Typed exceptions raised by the service tier ({@code RecordNotFoundException},
 * {@code DuplicateKeyException} on the CT02 add, {@code LogicError}) are deliberately <em>not</em>
 * caught here; they propagate to {@code exception.GlobalExceptionHandler}. Constructor dependency
 * injection with {@code private final} collaborators (no Lombok, no field injection); a
 * {@link Controller} (never {@code @RestController}) returning logical Thymeleaf view names, so no
 * REST/JSON surface is introduced. Compiles warning-free under {@code --release 25} with
 * {@code -Xlint:all}.</p>
 *
 * @see TransactionListService
 * @see TransactionViewService
 * @see TransactionAddService
 * @see CardDemoContext
 */
@Controller
public class TransactionController {

    /** Spring MVC model attribute name bound by the templates ({@code th:object="${form}"}). */
    private static final String ATTR_FORM = "form";

    /** Request-parameter name carrying the pressed PF-key from every transaction template. */
    private static final String PARAM_PFKEY = "pfkey";

    /**
     * Flash-attribute key that carries the {@code 'S'}-selected transaction id from the list
     * {@code POST} to the view {@code GET} (the COBOL {@code CDEMO-CT00-TRN-SELECTED} hand-off).
     */
    private static final String FLASH_SELECTED_TRAN_ID = "selectedTranId";

    /** Web PF-key token for the ENTER action submitted by the templates. */
    private static final String TOKEN_ENTER = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code PF3}). */
    private static final String TOKEN_PF_PREFIX = "PF";

    /** CICS AID mnemonic for ENTER, consumed by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix for program-function keys ({@code DFHPFnn}). */
    private static final String AID_PF_PREFIX = "DFHPF";

    /** Spring MVC redirect prefix used to mirror the COBOL {@code XCTL}. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Logical Thymeleaf view for CT00 ({@code COTRN00C}); resolves to {@code templates/COTRN00.html}. */
    private static final String VIEW_LIST = "COTRN00";

    /** Logical Thymeleaf view for CT01 ({@code COTRN01C}); resolves to {@code templates/COTRN01.html}. */
    private static final String VIEW_DETAIL = "COTRN01";

    /** Logical Thymeleaf view for CT02 ({@code COTRN02C}); resolves to {@code templates/COTRN02.html}. */
    private static final String VIEW_ADD = "COTRN02";

    /**
     * Neutral banner shown when data binding rejects a field for exceeding its declared
     * {@code @Size} width (review finding #11). Only reachable by a crafted client (the template
     * {@code maxlength} / 3270 field width makes it impossible otherwise), so it carries no COBOL
     * business message and simply re-displays the screen without performing any transaction work.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    /**
     * Message-line colour token for the error / default line on the {@code COTRN02} add screen,
     * reproducing the BMS map default {@code ERRMSG ... COLOR=RED} (finding #11). Consumed by
     * {@code COTRN02.html} via {@code th:classappend="${form.errmsgColor}"} ({@code .red} class).
     */
    private static final String MSG_COLOR_ERROR = "red";

    /**
     * Message-line colour token for the green success line on the {@code COTRN02} add screen,
     * reproducing the COBOL {@code MOVE DFHGREEN TO ERRMSGC OF COTRN2AO} on the "Transaction added
     * successfully" branch (COTRN02C:727, finding #11). Matches the template's {@code .green} class.
     */
    private static final String MSG_COLOR_GREEN = "green";

    /**
     * Message-line colour token for the neutral line, reproducing the BMS {@code DFHNEUTR}
     * (white) attribute (finding #11). Used for the Java-only over-width guard banner
     * ({@link #MSG_FIELD_LENGTH}), which carries no COBOL business-edit message and is therefore
     * rendered neutral so it never masquerades as a COBOL red error. Matches the template's
     * {@code .neutral} class.
     */
    private static final String MSG_COLOR_NEUTRAL = "neutral";

    /** Route for CT00 (transaction browse). */
    private static final String ROUTE_LIST = "/transaction/list";

    /** Route for CT01 (transaction view). */
    private static final String ROUTE_VIEW = "/transaction/view";

    /** Route for CT02 (transaction add). */
    private static final String ROUTE_ADD = "/transaction/add";

    /** COBOL {@code WS-TRANID VALUE 'CT00'} / {@code WS-PGMNAME VALUE 'COTRN00C'} for the list screen. */
    private static final String TRAN_LIST = "CT00";

    /** COBOL program name for the list screen. */
    private static final String PGM_LIST = "COTRN00C";

    /** COBOL {@code WS-TRANID VALUE 'CT01'} for the view screen. */
    private static final String TRAN_VIEW = "CT01";

    /** COBOL program name for the view screen. */
    private static final String PGM_VIEW = "COTRN01C";

    /** COBOL {@code WS-TRANID VALUE 'CT02'} for the add screen. */
    private static final String TRAN_ADD = "CT02";

    /** COBOL program name for the add screen. */
    private static final String PGM_ADD = "COTRN02C";

    /**
     * Authoritative COBOL-program-name &rarr; web-route map, keyed by the value each service records
     * on {@link CardDemoContext#getToProgram()} for a redirect (the COBOL {@code XCTL} destination).
     * Covers the sign-on program, the main menu, and the three transaction screens &mdash; the only
     * hand-off targets reachable from {@code COTRN00C}/{@code COTRN01C}/{@code COTRN02C}. The map is
     * immutable and never exposed, so it introduces no shared mutable state; unmapped targets degrade
     * safely to the main menu via {@link #routeForProgram(String)}.
     */
    private static final Map<String, String> PROGRAM_ROUTES = Map.of(
            "COSGN00C", "/signon",
            "COMEN01C", "/menu",
            "COTRN00C", ROUTE_LIST,
            "COTRN01C", ROUTE_VIEW,
            "COTRN02C", ROUTE_ADD);

    /** Fallback route when {@link CardDemoContext#getToProgram()} is unmapped (defensive; the main menu). */
    private static final String DEFAULT_ROUTE = "/menu";

    /**
     * Transaction-browse business service (migration of {@code COTRN00C} paragraphs). Owns paging,
     * the ten-row page size, and the {@code 'S'}-selection contract; this controller only requests
     * pages and routes selections.
     */
    private final TransactionListService transactionListService;

    /**
     * Transaction-view business service (migration of {@code COTRN01C} paragraphs). Reads a single
     * transaction by id and populates the read-only display fields.
     */
    private final TransactionViewService transactionViewService;

    /**
     * Transaction-add business service (migration of {@code COTRN02C} paragraphs). Validates the key
     * and data fields and performs the {@code @Transactional} add.
     */
    private final TransactionAddService transactionAddService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a scoped proxy and
     * shared with the three services, so all observe the same per-session navigation and identity
     * state; this controller reads {@link CardDemoContext#getToProgram()} to route redirects.
     */
    private final CardDemoContext context;

    /**
     * Creates the transaction controller with its injected collaborators.
     *
     * <p>The constructor only stores the four references and invokes no overridable method, so it is
     * free of the {@code this-escape} lint category under the zero-warning build.</p>
     *
     * @param transactionListService the CT00 browse service; must not be {@code null}
     * @param transactionViewService the CT01 view service; must not be {@code null}
     * @param transactionAddService  the CT02 add service; must not be {@code null}
     * @param context                the session-scoped {@link CardDemoContext} (COMMAREA
     *                               replacement); must not be {@code null}
     */
    public TransactionController(TransactionListService transactionListService,
            TransactionViewService transactionViewService,
            TransactionAddService transactionAddService,
            CardDemoContext context) {
        this.transactionListService = transactionListService;
        this.transactionViewService = transactionViewService;
        this.transactionAddService = transactionAddService;
        this.context = context;
    }

    // ============================================================================================
    // CT00 - Transaction browse (COTRN00C)
    // ============================================================================================

    /**
     * Displays the transaction browse screen - the initial (first-display) path of CICS tran
     * {@code CT00} / program {@code COTRN00C}.
     *
     * <p>Unlike the menu programs, {@code COTRN00C} always performs {@code PROCESS-ENTER-KEY} on its
     * first display (COBOL {@code MAIN-PARA} lines 112-116), so this {@code GET} delegates straight to
     * {@link TransactionListService#mainEntry(TransactionListService.AidKey, COTRN00Form)}, whose
     * first-entry branch loads and populates page one. When there is no COMMAREA
     * ({@link CardDemoContext#isNew()}) the service bounces to sign-on and the recorded target program
     * is turned into a redirect; otherwise the populated list is rendered.</p>
     *
     * @param form the transaction-list screen form (map {@code COTRN0A}), bound under
     *             {@link #ATTR_FORM} so the {@code COTRN00} template can render it
     * @return the logical view name {@link #VIEW_LIST}, or a {@code redirect:} on first-entry bounce
     */
    @GetMapping(ROUTE_LIST)
    public String showTransactionList(@ModelAttribute(ATTR_FORM) COTRN00Form form) {
        TransactionListResult result = transactionListService.mainEntry(TransactionListService.AidKey.ENTER, form);
        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        return renderList(form, result);
    }

    /**
     * Handles a transaction-browse submission - the {@code RECEIVE-TRNLST-SCREEN} +
     * {@code EVALUATE EIBAID} path of CICS tran {@code CT00} / program {@code COTRN00C} (COBOL
     * {@code MAIN-PARA} lines 118-135).
     *
     * <p>The pressed key is resolved to a {@link PfKey} and mapped to the service's
     * {@link TransactionListService.AidKey}: {@code ENTER} applies the optional numeric
     * {@code TRNIDIN} filter and the per-row {@code 'S'} selection ({@code PROCESS-ENTER-KEY});
     * {@code PF7}/{@code PF8} page backward/forward ({@code PROCESS-PF7-KEY}/{@code PROCESS-PF8-KEY});
     * {@code PF3} transfers to the main menu {@code COMEN01C} ({@code RETURN-TO-PREV-SCREEN}); any
     * other key yields the invalid-key message. When the ENTER path resolves an {@code 'S'} selection
     * the service records the view program on the context and returns the chosen transaction id, which
     * is placed in the {@value #FLASH_SELECTED_TRAN_ID} flash attribute before the redirect to
     * {@link #ROUTE_VIEW} so {@code COTRN01C} can pre-load it (the COBOL {@code CDEMO-CT00-TRN-SELECTED}
     * hand-off). The numeric-filter ("Tran ID must be Numeric ...") and invalid-selection
     * ("Invalid selection. Valid value is S") messages are produced by the service and rendered
     * verbatim.</p>
     *
     * @param form               the submitted list form (map {@code COTRN0A}) supplying the filter
     *                           and the ten selection cells; bound under {@link #ATTR_FORM}
     * @param pfkey              the activated PF-key token; defaults to {@link #TOKEN_ENTER}
     * @param redirectAttributes carrier for the one-shot selected-transaction-id flash attribute
     * @return a {@code redirect:} to the selected view / menu / sign-on, or {@link #VIEW_LIST}
     */
    @PostMapping(ROUTE_LIST)
    public String handleTransactionList(@Valid @ModelAttribute(ATTR_FORM) COTRN00Form form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = TOKEN_ENTER) String pfkey,
            RedirectAttributes redirectAttributes) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the list header with a neutral banner
        // and performs no browse, so the COBOL transaction-list edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            return VIEW_LIST;
        }
        TransactionListResult result = transactionListService.mainEntry(toListAid(resolvePfKey(pfkey)), form);
        if (result.isRedirect()) {
            if (result.hasSelection()) {
                // MOVE CDEMO-CT00-TRN-SELECTED across the XCTL to COTRN01C via a one-shot flash.
                redirectAttributes.addFlashAttribute(FLASH_SELECTED_TRAN_ID, result.selectedTransactionId());
            }
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        return renderList(form, result);
    }

    /**
     * Renders the transaction-browse screen - the controller half of {@code SEND-TRNLST-SCREEN}.
     *
     * <p>The service has already populated the ten data rows and the page-number field on the form;
     * this method adds the fixed header ({@code POPULATE-HEADER-INFO}) and places the service's
     * message (a boundary, invalid-selection or numeric-filter note, or {@code null}) on the message
     * line.</p>
     *
     * @param form   the form to render (data rows already populated by the service)
     * @param result the service outcome supplying the optional message
     * @return the logical view name {@link #VIEW_LIST}
     */
    private String renderList(COTRN00Form form, TransactionListResult result) {
        populateHeader(form);
        form.setErrmsg(result.message());
        return VIEW_LIST;
    }

    // ============================================================================================
    // CT01 - Transaction view (COTRN01C)
    // ============================================================================================

    /**
     * Displays the transaction-view screen - the initial (first-display) path of CICS tran
     * {@code CT01} / program {@code COTRN01C} (COBOL {@code MAIN-PARA} lines 99-109).
     *
     * <p>Reads the {@value #FLASH_SELECTED_TRAN_ID} flash attribute set by the list screen and hands
     * it to {@link TransactionViewService#mainEntry(COTRN01Form, TransactionViewService.AidKey, String)}
     * as the COBOL {@code CDEMO-CT01-TRN-SELECTED}. On first entry the service pre-loads that id into
     * {@code TRNIDIN} and performs {@code READ-TRANSACT-FILE} (populating the read-only display fields);
     * when no id was forwarded (the operator reached the screen from the main menu) a blank screen is
     * shown for manual entry. When there is no COMMAREA ({@link CardDemoContext#isNew()}) the service
     * bounces to sign-on and the recorded target program is turned into a redirect.</p>
     *
     * @param form  the transaction-view screen form (map {@code COTRN1A}), bound under
     *              {@link #ATTR_FORM} so the {@code COTRN01} template can render it
     * @param model supplies the selected-transaction-id flash attribute forwarded from the list
     * @return the logical view name {@link #VIEW_DETAIL}, or a {@code redirect:} on first-entry bounce
     */
    @GetMapping(ROUTE_VIEW)
    public String showTransactionView(@ModelAttribute(ATTR_FORM) COTRN01Form form, Model model) {
        // Finding #10 (INFO): a GET of the CT01 route is the web-tier equivalent of arriving via an
        // XCTL/redirect, and every legitimate transfer into COTRN01C first sets
        // CDEMO-PGM-CONTEXT = 0 - the transaction-list dispatch does MOVE 0 TO CDEMO-PGM-CONTEXT
        // before its XCTL (legacy/cbl/COTRN00C.cbl line 191) and the menu likewise (MOVE ZEROS) -
        // so the arriving program always sees PGM-ENTER on its first display. Re-seat
        // CDEMO-PGM-ENTER here so a direct or bookmarked GET (whose session context may still hold
        // a stale CDEMO-PGM-REENTER left by a prior screen) takes the first-display branch of
        // TransactionViewService.mainEntry - a clean empty search screen, or the list-forwarded
        // selection pre-load - instead of falling through to EVALUATE EIBAID -> WHEN DFHENTER on an
        // empty form, which surfaced a spurious "Tran ID can NOT be empty..." banner. markEnter()
        // touches only CDEMO-PGM-CONTEXT, never EIBCALEN (isNew), so the cold first-entry bounce to
        // sign-on is preserved; the one-shot selected-transaction flash still drives the
        // list -> view pre-load; and genuine submissions arrive through handleTransactionView
        // (POST) with the real AID and are unaffected.
        context.markEnter();
        String selectedTranId = readSelectedTranId(model);
        TransactionViewResult result =
                transactionViewService.mainEntry(form, TransactionViewService.AidKey.ENTER, selectedTranId);
        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        return renderView(form, result);
    }

    /**
     * Handles a transaction-view submission - the {@code RECEIVE-TRNVIEW-SCREEN} +
     * {@code EVALUATE EIBAID} path of CICS tran {@code CT01} / program {@code COTRN01C} (COBOL
     * {@code MAIN-PARA} lines 111-132).
     *
     * <p>The pressed key is resolved to a {@link PfKey} and mapped to the service's
     * {@link TransactionViewService.AidKey}: {@code ENTER} reads and displays the entered transaction
     * ({@code PROCESS-ENTER-KEY} / {@code READ-TRANSACT-FILE}); {@code PF4} clears the screen
     * ({@code CLEAR-CURRENT-SCREEN} / {@code INITIALIZE-ALL-FIELDS}); {@code PF5} transfers to the
     * transaction list {@code COTRN00C} (redirect to {@link #ROUTE_LIST}); {@code PF3} returns to the
     * calling program ({@code CDEMO-FROM-PROGRAM}, or the main menu when blank); any other key yields
     * the invalid-key message. A {@code NOTFND} on the read surfaces as a {@code RecordNotFoundException}
     * that propagates to the global handler (it is intentionally not swallowed here).</p>
     *
     * @param form  the submitted view form (map {@code COTRN1A}) supplying {@code TRNIDIN}; bound
     *              under {@link #ATTR_FORM}
     * @param pfkey the activated PF-key token; defaults to {@link #TOKEN_ENTER}
     * @return a {@code redirect:} to the list / caller / sign-on, or {@link #VIEW_DETAIL}
     */
    @PostMapping(ROUTE_VIEW)
    public String handleTransactionView(@Valid @ModelAttribute(ATTR_FORM) COTRN01Form form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = TOKEN_ENTER) String pfkey) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the view header with a neutral banner
        // and performs no lookup, so the COBOL transaction-view edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            return VIEW_DETAIL;
        }
        TransactionViewResult result =
                transactionViewService.mainEntry(form, toViewAid(resolvePfKey(pfkey)), null);
        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        return renderView(form, result);
    }

    /**
     * Renders the transaction-view screen - the controller half of {@code SEND-TRNVIEW-SCREEN}.
     *
     * <p>The service has already populated (or cleared) the read-only display fields on the form;
     * this method adds the fixed header ({@code POPULATE-HEADER-INFO}) and places the service's
     * optional error message on the message line.</p>
     *
     * @param form   the form to render (display fields already set by the service)
     * @param result the service outcome supplying the optional error message
     * @return the logical view name {@link #VIEW_DETAIL}
     */
    private String renderView(COTRN01Form form, TransactionViewResult result) {
        populateHeader(form);
        form.setErrmsg(result.message());
        return VIEW_DETAIL;
    }

    // ============================================================================================
    // CT02 - Transaction add (COTRN02C)
    // ============================================================================================

    /**
     * Displays the add-transaction screen - the initial (first-display) path of CICS tran
     * {@code CT02} / program {@code COTRN02C} (COBOL {@code MAIN-PARA} lines 120-131).
     *
     * <p>Delegates to {@link TransactionAddService#mainEntry(COTRN02Form, PfKey)}, whose first-entry
     * branch positions the cursor on the account-id field and shows an empty screen - unless a card
     * was pre-selected upstream ({@code CDEMO-CT02-TRN-SELECTED}, held on
     * {@link CardDemoContext#getCardNum()}), in which case it is copied into the card-number input and
     * the enter-key path runs immediately. When there is no COMMAREA ({@link CardDemoContext#isNew()})
     * the service bounces to sign-on and the recorded target program is turned into a redirect.</p>
     *
     * @param form the add-transaction screen form (map {@code COTRN2A}), bound under {@link #ATTR_FORM}
     *             so the {@code COTRN02} template can render it
     * @return the logical view name {@link #VIEW_ADD}, or a {@code redirect:} on first-entry bounce
     */
    @GetMapping(ROUTE_ADD)
    public String showTransactionAdd(@ModelAttribute(ATTR_FORM) COTRN02Form form) {
        // Finding #10 (INFO): a GET of the CT02 route is the web-tier equivalent of arriving via an
        // XCTL/redirect, and every legitimate transfer into COTRN02C first sets
        // CDEMO-PGM-CONTEXT = 0 - the main menu does MOVE ZEROS before its XCTL, and the
        // card-detail/transaction-list dispatch that pre-selects a card sets pgmContext to enter -
        // so the arriving program always sees PGM-ENTER on its first display. Re-seat
        // CDEMO-PGM-ENTER here so a direct or bookmarked GET (whose session context may still hold a
        // stale CDEMO-PGM-REENTER left by a prior screen) takes the first-display branch of
        // TransactionAddService.mainEntry - a clean empty add screen, or the pre-selected-card
        // pre-load - instead of falling through to EVALUATE EIBAID -> WHEN DFHENTER ->
        // PROCESS-ENTER-KEY on an empty form, which surfaced a spurious
        // "Account or Card Number must be entered..." banner. This is the same root cause and fix
        // as the sibling CT01 view path above. markEnter() touches only CDEMO-PGM-CONTEXT, never
        // EIBCALEN (isNew), so the cold first-entry bounce to sign-on is preserved; the
        // pre-selected-card pre-load still runs (it keys on the ENTER state that markEnter
        // guarantees plus the card carried on the session context); and genuine submissions arrive
        // through handleTransactionAdd (POST) with the real AID and are unaffected.
        context.markEnter();
        TransactionAddResult result = transactionAddService.mainEntry(form, PfKey.ENTER);
        if (result.action() == ScreenAction.REDIRECT) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        return renderAdd(form, result);
    }

    /**
     * Handles an add-transaction submission - the {@code RECEIVE-TRNADD-SCREEN} +
     * {@code EVALUATE EIBAID} path of CICS tran {@code CT02} / program {@code COTRN02C} (COBOL
     * {@code MAIN-PARA} lines 132-155).
     *
     * <p>The pressed key is resolved to a {@link PfKey} and passed directly to the service (which
     * models the COBOL {@code EVALUATE EIBAID} on that shared type): {@code ENTER} validates the key
     * and data fields and, on confirmation, performs the {@code @Transactional} add
     * ({@code PROCESS-ENTER-KEY} &rarr; {@code ADD-TRANSACTION}); {@code PF4} clears the screen
     * ({@code CLEAR-CURRENT-SCREEN}); {@code PF5} copies the last transaction's data into the form
     * ({@code COPY-LAST-TRAN-DATA}); {@code PF3} returns to the caller ({@code CDEMO-FROM-PROGRAM}, or
     * the main menu when blank); any other key yields the invalid-key message. A duplicate transaction
     * id raises a {@code DuplicateKeyException} that propagates to the global handler (it is
     * intentionally not swallowed here); amount parsing/formatting and date validation are performed by
     * the service via {@code CobolDecimal} / {@code DateConversionService}.</p>
     *
     * @param form  the submitted add form (map {@code COTRN2A}) supplying the key, data, and confirm
     *              fields; bound under {@link #ATTR_FORM}
     * @param pfkey the activated PF-key token; defaults to {@link #TOKEN_ENTER}
     * @return a {@code redirect:} to the caller / sign-on, or {@link #VIEW_ADD}
     */
    @PostMapping(ROUTE_ADD)
    public String handleTransactionAdd(@Valid @ModelAttribute(ATTR_FORM) COTRN02Form form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = TOKEN_ENTER) String pfkey) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the add screen with a neutral banner
        // and performs no add, so the COBOL transaction-add edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            form.setErrmsgColor(MSG_COLOR_NEUTRAL);
            return VIEW_ADD;
        }
        TransactionAddResult result = transactionAddService.mainEntry(form, resolvePfKey(pfkey));
        if (result.action() == ScreenAction.REDIRECT) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        return renderAdd(form, result);
    }

    /**
     * Renders the add-transaction screen - the controller half of {@code SEND-TRNADD-SCREEN}.
     *
     * <p>The service has already populated / cleared the entry fields on the form; this method adds
     * the fixed header ({@code POPULATE-HEADER-INFO}) and places the service's message (an error, the
     * "Confirm to add this transaction..." prompt, or the green "Transaction added successfully..."
     * note) on the message line. The message text is preserved verbatim; the COBOL error/green color
     * distinction is a template concern.</p>
     *
     * @param form   the form to render (entry fields already set by the service)
     * @param result the service outcome supplying the optional message
     * @return the logical view name {@link #VIEW_ADD}
     */
    private String renderAdd(COTRN02Form form, TransactionAddResult result) {
        populateHeader(form);
        form.setErrmsg(result.message());
        // Finding #11: colour the ERRMSG line from the service severity, reproducing the COBOL
        // MOVE DFHGREEN TO ERRMSGC on the "added successfully" branch (green) versus the red error.
        form.setErrmsgColor(colorFor(result.severity()));
        return VIEW_ADD;
    }

    /**
     * Maps a {@link TransactionAddService.MessageSeverity} to the semantic 3270 colour token for the
     * {@code COTRN02} {@code ERRMSG} line, reproducing the COBOL {@code MOVE DFHxxx TO ERRMSGC}
     * (finding #11): {@code INFORMATION} &rarr; {@link #MSG_COLOR_GREEN} (the {@code DFHGREEN}
     * "Transaction added successfully" line), and {@code ERROR}/{@code NONE} &rarr;
     * {@link #MSG_COLOR_ERROR} (the BMS default red).
     *
     * @param severity the service message severity; must not be {@code null}
     * @return the colour token consumed by {@code th:classappend="${form.errmsgColor}"}
     */
    private static String colorFor(TransactionAddService.MessageSeverity severity) {
        return switch (severity) {
            case INFORMATION -> MSG_COLOR_GREEN;
            case ERROR, NONE -> MSG_COLOR_ERROR;
        };
    }

    // ============================================================================================
    // Private helpers - PF-key resolution, routing, and header population
    // ============================================================================================

    /**
     * Reads the selected-transaction-id flash attribute forwarded from the list screen.
     *
     * <p>Spring merges the one-shot flash attribute set by {@link #handleTransactionList} into the
     * target request's model; this helper extracts it, tolerating its absence (a direct arrival from
     * the main menu carries no selection).</p>
     *
     * @param model the model that may carry the {@value #FLASH_SELECTED_TRAN_ID} flash attribute
     * @return the selected transaction id, or {@code null} when none was forwarded
     */
    private static String readSelectedTranId(Model model) {
        Object value = model.getAttribute(FLASH_SELECTED_TRAN_ID);
        return (value instanceof String selected) ? selected : null;
    }

    /**
     * Resolves the web PF-key token to the shared {@link PfKey}, routing through
     * {@link PfKeyHandler} exactly as the COBOL {@code EVALUATE EIBAID} did.
     *
     * <p>The transaction templates submit the human-facing labels {@code "ENTER"} and {@code "PFnn"}
     * (mirroring the 3270 keyboard), whereas {@link PfKeyHandler#fromAid(String)} recognizes CICS AID
     * mnemonics ({@code DFHENTER}, {@code DFHPFnn}). This helper maps the label to its AID mnemonic
     * before delegating, so PF-key resolution stays centralized in {@link PfKeyHandler}. A
     * {@code null}, blank, or unrecognized token yields {@link PfKey#OTHER}, matching the COBOL
     * {@code WHEN OTHER} branch; the lookup is upper-cased with {@link Locale#ROOT} so it never varies
     * with the default locale.</p>
     *
     * @param pfkey the raw PF-key token from the request (may be {@code null})
     * @return the resolved {@link PfKey}; {@link PfKey#OTHER} for {@code null}, blank, or unrecognized
     *         input
     */
    private static PfKey resolvePfKey(String pfkey) {
        if (pfkey == null) {
            return PfKey.OTHER;
        }
        String token = pfkey.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return PfKey.OTHER;
        }
        if (TOKEN_ENTER.equals(token)) {
            return PfKeyHandler.fromAid(AID_ENTER);
        }
        if (token.startsWith(TOKEN_PF_PREFIX)) {
            return PfKeyHandler.fromAid(AID_PF_PREFIX + token.substring(TOKEN_PF_PREFIX.length()));
        }
        return PfKey.OTHER;
    }

    /**
     * Maps the resolved {@link PfKey} to the transaction-list service's {@link TransactionListService.AidKey},
     * reproducing the {@code COTRN00C} {@code EVALUATE EIBAID} (ENTER / PF3 / PF7 / PF8, else the
     * {@code WHEN OTHER} default).
     *
     * @param key the resolved PF-key
     * @return the matching list {@link TransactionListService.AidKey}
     */
    private static TransactionListService.AidKey toListAid(PfKey key) {
        return switch (key) {
            case ENTER -> TransactionListService.AidKey.ENTER;
            case PFK03 -> TransactionListService.AidKey.PF3;
            case PFK07 -> TransactionListService.AidKey.PF7;
            case PFK08 -> TransactionListService.AidKey.PF8;
            default -> TransactionListService.AidKey.OTHER;
        };
    }

    /**
     * Maps the resolved {@link PfKey} to the transaction-view service's {@link TransactionViewService.AidKey},
     * reproducing the {@code COTRN01C} {@code EVALUATE EIBAID} (ENTER / PF3 / PF4 / PF5, else the
     * {@code WHEN OTHER} default).
     *
     * @param key the resolved PF-key
     * @return the matching view {@link TransactionViewService.AidKey}
     */
    private static TransactionViewService.AidKey toViewAid(PfKey key) {
        return switch (key) {
            case ENTER -> TransactionViewService.AidKey.ENTER;
            case PFK03 -> TransactionViewService.AidKey.PFK03;
            case PFK04 -> TransactionViewService.AidKey.PFK04;
            case PFK05 -> TransactionViewService.AidKey.PFK05;
            default -> TransactionViewService.AidKey.OTHER;
        };
    }

    /**
     * Maps a COBOL target program name (the {@code XCTL} destination recorded on
     * {@link CardDemoContext#getToProgram()}) to its Spring MVC route.
     *
     * <p>The mapping is authoritative and traceable to {@code legacy/csd/CARDDEMO.CSD}; an unmapped or
     * {@code null} program degrades safely to the main menu ({@link #DEFAULT_ROUTE}) rather than
     * emitting an unresolved redirect.</p>
     *
     * @param programName the COBOL target program name (for example {@code COTRN01C})
     * @return the redirect route for the program, or {@link #DEFAULT_ROUTE} when unmapped
     */
    private static String routeForProgram(String programName) {
        if (programName == null) {
            return DEFAULT_ROUTE;
        }
        return PROGRAM_ROUTES.getOrDefault(programName.trim().toUpperCase(Locale.ROOT), DEFAULT_ROUTE);
    }

    /**
     * Populates the fixed CT00 screen header - migration of {@code POPULATE-HEADER-INFO} for
     * {@code COTRN00C}.
     *
     * @param form the list form whose header fields are set
     */
    private void populateHeader(COTRN00Form form) {
        form.setTrnname(TRAN_LIST);
        form.setPgmname(PGM_LIST);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the fixed CT01 screen header - migration of {@code POPULATE-HEADER-INFO} for
     * {@code COTRN01C}.
     *
     * @param form the view form whose header fields are set
     */
    private void populateHeader(COTRN01Form form) {
        form.setTrnname(TRAN_VIEW);
        form.setPgmname(PGM_VIEW);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the fixed CT02 screen header - migration of {@code POPULATE-HEADER-INFO} for
     * {@code COTRN02C}.
     *
     * @param form the add form whose header fields are set
     */
    private void populateHeader(COTRN02Form form) {
        form.setTrnname(TRAN_ADD);
        form.setPgmname(PGM_ADD);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Restricts request-parameter binding to the fields each transaction screen actually submits
     * (review finding #11), switching on the bound form type since all three screens share the
     * {@code form} model attribute. The browse screen submits the transaction-id search key, the
     * ten per-row selectors, and the ten per-row transaction ids; the view screen submits only the
     * transaction-id key; the add screen submits its entry fields. {@code pfkey} arrives as a
     * {@code @RequestParam} and is not bound through the form.
     *
     * <p>The ten {@code trnid0N} cells are intentionally allow-listed because they round-trip from
     * the screen: the legacy BMS map defines each {@code TRNIDnn} field with {@code ATTRB=(ASKIP,
     * FSET,...)} so the pre-set modified-data tag re-transmits the field on ENTER, and
     * {@code COTRN00C} paragraph {@code PROCESS-ENTER-KEY} resolves the chosen row by reading
     * {@code TRNIDnnI} from the received map (never a re-browsed value). Preserving that contract
     * requires the browser to re-submit {@code trnid0N} (rendered as hidden inputs in
     * {@code COTRN00.html}) and this binder to accept them. The page counter {@code pagenum}
     * ({@code CDEMO-CT00-PAGE-NUM}) is likewise part of the pseudo-conversational cursor state: the
     * COBOL carries it in the COMMAREA across turns ({@code COTRN00C} lines 111/140/194/520) and
     * reads it back in {@code PROCESS-PF7-KEY} ({@code IF CDEMO-CT00-PAGE-NUM > 1}, line 245) to
     * decide whether a previous page exists before paging backward, so it must round-trip too and
     * is accepted here (review finding #9). The remaining display cells ({@code tdate0N},
     * {@code tdesc0N}, {@code tamt00N}) are never read back by the COBOL — they are re-derived from
     * the file on each SEND — so they stay excluded and cannot be over-posted. Display-only
     * header/title/date/message fields are likewise excluded.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget() is null
        // when @InitBinder runs; the resolved binder.getTargetType() is the reliable discriminator
        // (it is null for simple @RequestParam binders such as pfkey).
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (COTRN00Form.class.equals(targetType)) {
            binder.setAllowedFields(
                    "trnidin",
                    // CDEMO-CT00-PAGE-NUM: pseudo-conversational page counter carried across the
                    // turn and read back in PROCESS-PF7-KEY to gate backward paging (finding #9).
                    "pagenum",
                    "sel0001", "sel0002", "sel0003", "sel0004", "sel0005",
                    "sel0006", "sel0007", "sel0008", "sel0009", "sel0010",
                    "trnid01", "trnid02", "trnid03", "trnid04", "trnid05",
                    "trnid06", "trnid07", "trnid08", "trnid09", "trnid10");
        } else if (COTRN01Form.class.equals(targetType)) {
            binder.setAllowedFields("trnidin");
        } else if (COTRN02Form.class.equals(targetType)) {
            binder.setAllowedFields(
                    "actidin", "cardnin", "ttypcd", "tcatcd", "trnsrc", "tdesc",
                    "trnamt", "torigdt", "tprocdt", "mid", "mname", "mcity", "mzip", "confirm");
        }
    }
}
