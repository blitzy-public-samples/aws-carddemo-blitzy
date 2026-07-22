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
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.DateStruct;
import com.aws.carddemo.dto.screen.COCRDLIForm;
import com.aws.carddemo.dto.screen.COCRDSLForm;
import com.aws.carddemo.dto.screen.COCRDUPForm;
import com.aws.carddemo.service.online.CardDetailService;
import com.aws.carddemo.service.online.CardDetailService.CardDetailResult;
import com.aws.carddemo.service.online.CardListService;
import com.aws.carddemo.service.online.CardListService.CardListPagingState;
import com.aws.carddemo.service.online.CardListService.CardListResult;
import com.aws.carddemo.service.online.CardUpdateService;
import com.aws.carddemo.service.online.CardUpdateService.CardUpdateResult;
import com.aws.carddemo.service.online.CardUpdateService.CardUpdateState;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.ScreenTitles;
import jakarta.servlet.http.HttpSession;
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

/**
 * Spring MVC controller for the AWS CardDemo credit-card screens (list, detail, update).
 *
 * <p>This class is the web-tier replacement for three CICS pseudo-conversational card
 * transactions, and documents a fourth security-only transaction that has no COBOL business
 * source (all mappings verified in {@code legacy/csd/CARDDEMO.CSD}):</p>
 * <ul>
 *   <li><b>{@code CCLI} (list)</b> &rarr; program {@code COCRDLIC} &mdash; origin
 *       {@code legacy/cbl/COCRDLIC.cbl}; BMS mapset {@code COCRDLI} / map {@code CCRDLIA};
 *       route {@code /card/list}; view {@code COCRDLI}. Delegated paragraphs:
 *       {@code 0000-MAIN}, {@code 2200-EDIT-INPUTS} / {@code 2210-EDIT-ACCOUNT} /
 *       {@code 2220-EDIT-CARD} / {@code 2250-EDIT-ARRAY}, {@code 9000-READ-FORWARD} /
 *       {@code 9100-READ-BACKWARDS} / {@code 9500-FILTER-RECORDS}
 *       (see {@link CardListService}).</li>
 *   <li><b>{@code CCDL} (detail / view)</b> &rarr; program {@code COCRDSLC} &mdash; origin
 *       {@code legacy/cbl/COCRDSLC.cbl}; BMS mapset {@code COCRDSL} / map {@code CCRDSLA};
 *       route {@code /card/detail}; view {@code COCRDSL}. Delegated paragraphs:
 *       {@code 0000-MAIN}, {@code 2000-PROCESS-INPUTS} / {@code 2200-EDIT-MAP-INPUTS},
 *       {@code 9000-READ-DATA} / {@code 9100-GETCARD-BYACCTCARD} /
 *       {@code 9150-GETCARD-BYACCT} (see {@link CardDetailService}).</li>
 *   <li><b>{@code CCUP} (update)</b> &rarr; program {@code COCRDUPC} &mdash; origin
 *       {@code legacy/cbl/COCRDUPC.cbl}; BMS mapset {@code COCRDUP} / map {@code CCRDUPA};
 *       route {@code /card/update}; view {@code COCRDUP}. Delegated paragraphs:
 *       {@code 0000-MAIN}, {@code 1000-PROCESS-INPUTS} / {@code 1200-EDIT-MAP-INPUTS},
 *       {@code 2000-DECIDE-ACTION}, {@code 9000-READ-DATA} / {@code 9200-WRITE-PROCESSING} /
 *       {@code 9300-CHECK-CHANGE-IN-REC} (see {@link CardUpdateService}). The controller owns
 *       the {@code 3000}-series screen build ({@code 3200-SETUP-SCREEN-VARS},
 *       {@code 3250-SETUP-INFOMSG}).</li>
 * </ul>
 *
 * <h2>CDV1 / COCRDSEC traceability note (AAP &sect;0.6.10 &mdash; 100% coverage, no gap)</h2>
 * <p>CICS transaction CDV1 -> program COCRDSEC has NO COBOL business source; it is a CICS-only
 * card-detail security variant realized as URL/method authorization in config.SecurityConfig
 * (see docs/decision-log.md and docs/traceability-matrix.md). No handler method exists here for
 * CDV1.</p>
 *
 * <h2>Invalid-key handling (verified-behavior note)</h2>
 * <p>Unlike the menu program {@code COMEN01C} (which displays an "invalid key" line), all three
 * card programs <em>coerce</em> any unsupported attention-id to ENTER and continue
 * ({@code COCRDLIC} "else force ENTER"; {@code COCRDSLC} "anything else &hellip; coerced to
 * ENTER"; {@code COCRDUPC} PF-key validity gate falls back to ENTER). That coercion lives in the
 * business services, so this controller always delegates the resolved key and never renders a
 * standalone invalid-key message for these screens; doing otherwise would be a behavioral
 * regression against the COBOL (AAP &sect;0.2.2, 100% parity). Consequently the card screens do
 * not use {@code util.constants.Messages}.</p>
 *
 * <h2>Responsibilities and constraints</h2>
 * <p>Presentation and navigation only: the controller resolves the pressed PF-key
 * ({@link PfKeyHandler} &rarr; {@link PfKey}), delegates all business logic to the injected
 * {@link CardListService}, {@link CardDetailService} and {@link CardUpdateService}, and then
 * renders the populated screen form or mirrors the COBOL {@code XCTL} with a Spring MVC
 * {@code redirect:}. It performs no repository access and no card arithmetic. The COBOL
 * pseudo-conversational COMMAREA ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}; the program-local {@code WS-THIS-PROGCOMMAREA} carriers (the
 * card-list paging cursor and the card-update snapshot) are held per-user in the
 * {@link HttpSession} and threaded into the services (AAP &sect;0.6.8).</p>
 *
 * <p>Constructor dependency injection, {@code private final} collaborators, no Lombok, no
 * field injection; a {@link Controller} (never {@code @RestController}) returning logical
 * Thymeleaf view names, so no REST/JSON surface is introduced (AAP &sect;0.3.4). Typed
 * exceptions raised by the service tier ({@code RecordNotFoundException}, {@code LogicError},
 * optimistic-lock translations) are deliberately <em>not</em> caught here; they propagate to
 * {@code exception.GlobalExceptionHandler} (AAP &sect;0.6.5). No monetary values are handled,
 * so no {@code float}/{@code double} appears. Compiles warning-free under {@code --release 25}
 * with {@code -Xlint:all}.</p>
 *
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 * @see CardDemoContext
 */
@Controller
public class CardController {

    // ------------------------------------------------------------------------
    // View names (logical Thymeleaf views; resolve to templates/<name>.html).
    // ------------------------------------------------------------------------

    /** Logical view for the card-list screen ({@code CCLI} / {@code COCRDLIC}). */
    private static final String VIEW_LIST = "COCRDLI";

    /** Logical view for the card-detail screen ({@code CCDL} / {@code COCRDSLC}). */
    private static final String VIEW_DETAIL = "COCRDSL";

    /** Logical view for the card-update screen ({@code CCUP} / {@code COCRDUPC}). */
    private static final String VIEW_UPDATE = "COCRDUP";

    /**
     * Model attribute driving the review-finding-#10 confirmation window: {@code true}
     * only while the update state is {@code CCUP-CHANGES-OK-NOT-CONFIRMED}, which makes
     * the editable fields read-only and echoes the single-use token to the confirm
     * screen (COCRDUP.html {@code th:readonly="${confirmMode}"}).
     */
    private static final String MODEL_ATTR_CONFIRM_MODE = "confirmMode";

    /**
     * Neutral banner shown when data binding rejects a field for exceeding its declared
     * {@code @Size} width (review finding #11). Only reachable by a crafted client (the template
     * {@code maxlength} / 3270 field width makes it impossible otherwise), so it carries no COBOL
     * business message and simply re-displays the screen without performing any card work.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    // ------------------------------------------------------------------------
    // Transaction ids and program names (COBOL LIT-THIS* literals; header text).
    // ------------------------------------------------------------------------

    /** COBOL {@code LIT-THISTRANID VALUE 'CCLI'} - card-list transaction id. */
    private static final String TRAN_LIST = "CCLI";

    /** COBOL {@code LIT-THISTRANID VALUE 'CCDL'} - card-detail transaction id. */
    private static final String TRAN_DETAIL = "CCDL";

    /** COBOL {@code LIT-THISTRANID VALUE 'CCUP'} - card-update transaction id. */
    private static final String TRAN_UPDATE = "CCUP";

    /** COBOL {@code LIT-THISPGM VALUE 'COCRDLIC'} - card-list program name. */
    private static final String PGM_LIST = "COCRDLIC";

    /** COBOL {@code LIT-THISPGM VALUE 'COCRDSLC'} - card-detail program name. */
    private static final String PGM_DETAIL = "COCRDSLC";

    /** COBOL {@code LIT-THISPGM VALUE 'COCRDUPC'} - card-update program name. */
    private static final String PGM_UPDATE = "COCRDUPC";

    // ------------------------------------------------------------------------
    // Redirect plumbing (COBOL XCTL -> Spring MVC redirect).
    // ------------------------------------------------------------------------

    /** Spring MVC redirect prefix used to mirror the COBOL {@code EXEC CICS XCTL}. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Web route of the card-list screen. */
    private static final String ROUTE_LIST = "/card/list";

    /** Web route of the card-detail screen. */
    private static final String ROUTE_DETAIL = "/card/detail";

    /** Web route of the card-update screen. */
    private static final String ROUTE_UPDATE = "/card/update";

    /** Web route of the main menu ({@code COMEN01C} / {@code CM00}). */
    private static final String ROUTE_MENU = "/menu";

    /**
     * Authoritative mapping from the COBOL {@code XCTL} target program name (recorded on
     * {@link CardDemoContext#getToProgram()} by the delegated service) to its web route. The
     * services set {@code toProgram}/{@code toTranid} on every {@link CardListService.Routing#REDIRECT}
     * / {@code REDIRECT} outcome; this controller only translates the program name to a route.
     * The map is immutable and never exposed, so it introduces no shared mutable state.
     */
    private static final Map<String, String> PROGRAM_ROUTES = Map.of(
            "COMEN01C", ROUTE_MENU,
            PGM_LIST, ROUTE_LIST,
            PGM_DETAIL, ROUTE_DETAIL,
            PGM_UPDATE, ROUTE_UPDATE);

    // ------------------------------------------------------------------------
    // PF-key web tokens (mirrors the sibling MenuController bridge to CICS AIDs).
    // ------------------------------------------------------------------------

    /** Request-parameter name carrying the pressed PF-key from a card screen. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Web PF-key token for the ENTER action submitted by a card template. */
    private static final String ENTER_TOKEN = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code PF3}). */
    private static final String PF_TOKEN_PREFIX = "PF";

    /** CICS AID mnemonic for ENTER, consumed by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix for program-function keys ({@code DFHPFnn}). */
    private static final String AID_PF_PREFIX = "DFHPF";

    // ------------------------------------------------------------------------
    // Session attribute keys for the program-local COMMAREA carriers.
    // ------------------------------------------------------------------------

    /**
     * {@link HttpSession} attribute holding the card-list program-local paging state
     * ({@code WS-THIS-PROGCOMMAREA}: keyset cursor + seven persisted rows). Preserved across the
     * pseudo-conversational turns exactly as the COBOL carried it in the two-part COMMAREA.
     */
    private static final String SESSION_PAGING_STATE = "cardListPagingState";

    /**
     * {@link HttpSession} attribute holding the card-update program-local state
     * ({@code WS-THIS-PROGCOMMAREA}: change-action flag + {@code CCUP-OLD-*}/{@code CCUP-NEW-*}
     * snapshots used for the optimistic lock). Preserved across the pseudo-conversational turns.
     */
    private static final String SESSION_UPDATE_STATE = "cardUpdateState";

    // ------------------------------------------------------------------------
    // Card-list presentation messages (1400-SETUP-MESSAGE; controller-owned).
    // ------------------------------------------------------------------------

    /** COBOL {@code WS-INFORM-REC-ACTIONS} info line for the card-list screen. */
    private static final String INFO_LIST_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** COBOL {@code 1400-SETUP-MESSAGE} boundary line when paging up past the first page. */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** COBOL {@code 1400-SETUP-MESSAGE} boundary line when paging down past the last page. */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    // ------------------------------------------------------------------------
    // Card-update presentation info messages (3250-SETUP-INFOMSG; controller-owned).
    // ------------------------------------------------------------------------

    /** COBOL {@code PROMPT-FOR-SEARCH-KEYS} - initial prompt to gather the search keys. */
    private static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

    /** COBOL {@code FOUND-CARDS-FOR-ACCOUNT} - a card was fetched and its details are shown. */
    private static final String INFO_FOUND_CARDS = "Details of selected card shown above";

    /** COBOL {@code PROMPT-FOR-CHANGES} - invite the operator to edit the presented fields. */
    private static final String INFO_PROMPT_FOR_CHANGES = "Update card details presented above.";

    /** COBOL {@code PROMPT-FOR-CONFIRMATION} - edits validated; press PF5 to commit. */
    private static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** COBOL {@code CONFIRM-UPDATE-SUCCESS} - the rewrite committed successfully. */
    private static final String INFO_CONFIRM_SUCCESS = "Changes committed to database";

    /** COBOL {@code INFORM-FAILURE} - the update could not be applied (lock / rewrite failure). */
    private static final String INFO_FAILURE = "Changes unsuccessful. Please try again";

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected, immutable).
    // ------------------------------------------------------------------------

    /** Card-list business service (migration of {@code COCRDLIC} paragraphs). */
    private final CardListService cardListService;

    /** Card-detail (view) business service (migration of {@code COCRDSLC} paragraphs). */
    private final CardDetailService cardDetailService;

    /** Card-update business service (migration of {@code COCRDUPC} paragraphs). */
    private final CardUpdateService cardUpdateService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a scoped proxy
     * and shared with the three card services, so all four observe the same per-session
     * navigation, identity, and selected account/card state.
     */
    private final CardDemoContext context;

    /**
     * Creates the card controller with its injected collaborators.
     *
     * <p>The constructor only stores the references and invokes no overridable method, so it is
     * free of the {@code this-escape} lint category under the zero-warning build.</p>
     *
     * @param cardListService   the card-list business service; must not be {@code null}
     * @param cardDetailService the card-detail business service; must not be {@code null}
     * @param cardUpdateService the card-update business service; must not be {@code null}
     * @param context           the session-scoped {@link CardDemoContext} (COMMAREA
     *                          replacement); must not be {@code null}
     */
    public CardController(CardListService cardListService,
                          CardDetailService cardDetailService,
                          CardUpdateService cardUpdateService,
                          CardDemoContext context) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
        this.context = context;
    }

    // ========================================================================
    // CCLI - card list (COCRDLIC).
    // ========================================================================

    /**
     * Displays the card-list screen on initial entry - the {@code CCLI} / {@code COCRDLIC}
     * fresh-entry path (COBOL {@code 0000-MAIN} with {@code EIBCALEN = 0}).
     *
     * <p>Delegates to {@link CardListService#mainEntry} with an ENTER attention-id so the service
     * lists the first page (the parity-critical seven rows) forward from the start of the
     * card-by-account alternate-index browse, then renders {@link #VIEW_LIST}.</p>
     *
     * @param form    the card-list form, bound as the {@code form} model attribute
     * @param session the HTTP session carrying the paging cursor (COMMAREA carrier)
     * @return the logical view name {@link #VIEW_LIST}
     */
    @GetMapping(ROUTE_LIST)
    public String displayList(@ModelAttribute("form") COCRDLIForm form, HttpSession session) {
        return processList(form, PfKey.ENTER, session);
    }

    /**
     * Handles a submission from the card-list screen - the {@code CCLI} / {@code COCRDLIC}
     * re-entry path (COBOL {@code 2000-RECEIVE-MAP} &rarr; {@code 2200-EDIT-INPUTS} &rarr;
     * {@code EVALUATE EIBAID}).
     *
     * <p>Resolves the pressed key and delegates to {@link CardListService#mainEntry}, which edits
     * the optional account/card filters ({@code 2210}/{@code 2220}) and the per-row selection
     * characters ({@code 2250-EDIT-ARRAY}), performs the forward/backward paging
     * ({@code 9000-READ-FORWARD}/{@code 9100-READ-BACKWARDS}), and, for a valid {@code 'S'} or
     * {@code 'U'} row selection, records the chosen account and card on the
     * {@link CardDemoContext} and requests transfer to the detail or update program. Supported
     * keys are ENTER, PF3 (exit to the main menu), PF7 (page up) and PF8 (page down); the service
     * remaps any other key to ENTER exactly as the COBOL does.</p>
     *
     * @param form    the submitted card-list form, bound as the {@code form} model attribute
     * @param pfkey   the PF-key token submitted by the screen (defaults to ENTER)
     * @param session the HTTP session carrying the paging cursor (COMMAREA carrier)
     * @return {@link #VIEW_LIST}, or a {@code redirect:} to the detail/update/menu route
     */
    @PostMapping(ROUTE_LIST)
    public String submitList(@Valid @ModelAttribute("form") COCRDLIForm form,
                             BindingResult bindingResult,
                             @RequestParam(name = PARAM_PFKEY, required = false,
                                     defaultValue = ENTER_TOKEN) String pfkey,
                             HttpSession session) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the list header with a neutral banner
        // and performs no lookup, so the COBOL card-list edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateHeaderList(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            return VIEW_LIST;
        }
        return processList(form, resolvePfKey(pfkey), session);
    }

    /**
     * Shared card-list flow backing both the GET (display) and POST (submit) entry points.
     *
     * <p>Resolves the session paging cursor (COBOL {@code WS-THIS-PROGCOMMAREA}), invokes the
     * card-list business service, and then either mirrors the COBOL {@code XCTL} with a
     * {@code redirect:} (row-selection dispatch or PF3 exit, whose target program the service has
     * recorded on the {@link CardDemoContext}) or renders the populated list screen. A fresh
     * per-request {@link CardWorkArea} serves as the service scratch area ({@code CC-WORK-AREAS})
     * and holds no state across turns.</p>
     *
     * @param form    the card-list form to edit and populate
     * @param key     the resolved attention-id
     * @param session the HTTP session carrying the paging cursor
     * @return {@link #VIEW_LIST}, or a {@code redirect:} route
     */
    private String processList(COCRDLIForm form, PfKey key, HttpSession session) {
        CardListPagingState paging = getOrCreatePagingState(session);
        CardWorkArea work = new CardWorkArea();
        // COBOL 2100-RECEIVE-SCREEN (legacy/cbl/COCRDLIC.cbl lines 969-970): move the submitted
        // account/card filter fields from the map into the CC work area so 2210-EDIT-ACCOUNT /
        // 2220-EDIT-CARD can validate and apply them. Without this the filters were silently
        // dropped and every list came back unfiltered (review finding #6).
        //   MOVE ACCTSIDI OF CCRDLIAI TO CC-ACCT-ID
        //   MOVE CARDSIDI OF CCRDLIAI TO CC-CARD-NUM
        work.setAcctId(form.getAcctsid());
        work.setCardNum(form.getCardsid());
        CardListResult result = cardListService.mainEntry(form, key, work, paging);
        if (result.routing() == CardListService.Routing.REDIRECT) {
            return redirectFor(context.getToProgram());
        }
        return renderList(form, paging, key, result);
    }

    /**
     * Populates and returns the card-list view - the controller-owned half of the COBOL
     * {@code 1000-SEND-MAP}, specifically {@code 1400-SETUP-MESSAGE} (the info/paging-boundary
     * lines that {@link CardListService} documents as controller-owned) plus the fixed header and
     * the page number.
     *
     * <p>The message selection reproduces the COBOL {@code EVALUATE TRUE} exactly, using the data
     * the service exposes to the web tier:</p>
     * <ol>
     *   <li>A business error (a structurally invalid filter, or "no records found") arrives as
     *       {@link CardListResult#errorMessage()}; it is shown on the error line and the
     *       action-hint info line is suppressed (COBOL {@code WHEN FLG-*FILTER-NOT-OK -> CONTINUE}
     *       and the trailing {@code NOT WS-NO-RECORDS-FOUND} guard).</li>
     *   <li>PF7 on the first page &rarr; {@code NO PREVIOUS PAGES TO DISPLAY}.</li>
     *   <li>PF8 with no further page and the last page already shown &rarr;
     *       {@code NO MORE PAGES TO DISPLAY}.</li>
     *   <li>PF8 with no further page (first time) &rarr; the action hint, and the
     *       last-page-shown flag is raised (COBOL {@code SET CA-LAST-PAGE-SHOWN TO TRUE}).</li>
     *   <li>Otherwise (a normal listing, including when a next page exists) &rarr; the action
     *       hint.</li>
     * </ol>
     *
     * @param form   the form whose header, page number and message lines are set
     * @param paging the paging state supplying the screen number and boundary flags
     * @param key    the resolved attention-id, needed for the PF7/PF8 boundary tests
     * @param result the service result carrying any business error line
     * @return the logical view name {@link #VIEW_LIST}
     */
    private String renderList(COCRDLIForm form, CardListPagingState paging, PfKey key,
                              CardListResult result) {
        populateHeaderList(form);
        // MOVE WS-CA-SCREEN-NUM TO PAGENOO OF CCRDLIAO.
        form.setPageno(String.valueOf(paging.getScreenNum()));

        String errmsg = result.errorMessage();   // WS-ERROR-MSG (never null per the record).
        boolean infoHint = false;                  // WS-INFO-MSG starts at WS-NO-INFO-MESSAGE.

        // 1400-SETUP-MESSAGE EVALUATE TRUE (legacy/cbl/COCRDLIC.cbl lines 897-923), in order.
        // Only a structurally invalid filter (FLG-*FILTER-NOT-OK) is preserved verbatim; the
        // 9000-READ-FORWARD "NO MORE RECORDS" line is an overridable default, so the paging
        // boundary WHENs below can replace it and raise CA-LAST-PAGE-SHOWN (review finding #7).
        if (result.filterError()) {
            // WHEN FLG-ACCTFILTER-NOT-OK / FLG-CARDFILTER-NOT-OK -> CONTINUE (keep WS-ERROR-MSG,
            // no action hint).
            infoHint = false;
        } else if (PfKeyHandler.isPf7(key) && paging.isFirstPage()) {
            // WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE.
            errmsg = MSG_NO_PREVIOUS_PAGES;
        } else if (PfKeyHandler.isPf8(key)
                && !paging.isNextPageExists() && paging.isLastPageShown()) {
            // WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS AND CA-LAST-PAGE-SHOWN: a second
            // forward key-press once the final page is already on screen replaces the
            // read-forward "NO MORE RECORDS" default with the paging-boundary line.
            errmsg = MSG_NO_MORE_PAGES;
        } else if (PfKeyHandler.isPf8(key) && !paging.isNextPageExists()) {
            // WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-NOT-EXISTS: the first time the final page is
            // reached keep the "NO MORE RECORDS" error line, show the action hint, and raise
            // CA-LAST-PAGE-SHOWN so the *next* F8 reports "NO MORE PAGES".
            infoHint = true;
            if (!paging.isLastPageShown()) {
                paging.setLastPageShown(true);   // SET CA-LAST-PAGE-SHOWN TO TRUE.
            }
        } else {
            // WHEN WS-NO-INFO-MESSAGE / WHEN CA-NEXT-PAGE-EXISTS -> the record-actions hint.
            infoHint = true;
        }

        form.setErrmsg(errmsg);     // MOVE WS-ERROR-MSG TO ERRMSGO (always).
        // IF NOT WS-NO-INFO-MESSAGE AND NOT WS-NO-RECORDS-FOUND -> MOVE WS-INFO-MSG TO INFOMSGO.
        form.setInfomsg(infoHint && !result.noRecordsFound() ? INFO_LIST_ACTIONS : null);
        return VIEW_LIST;
    }

    // ========================================================================
    // CCDL - card detail / view (COCRDSLC).
    // ========================================================================

    /**
     * Displays the card-detail (view) screen on initial entry - the {@code CCDL} /
     * {@code COCRDSLC} fresh-entry path (COBOL {@code 0000-MAIN} with {@code EIBCALEN = 0}).
     *
     * <p>Delegates to {@link CardDetailService#mainEntry} with an ENTER attention-id. When the
     * operator arrived from the card list, the service reads the selected account/card recorded
     * on the {@link CardDemoContext} (CARDAIX alternate-index access); otherwise it presents the
     * search screen. This is a read-only screen.</p>
     *
     * @param form the card-detail form, bound as the {@code form} model attribute
     * @return the logical view name {@link #VIEW_DETAIL}
     */
    @GetMapping(ROUTE_DETAIL)
    public String displayDetail(@ModelAttribute("form") COCRDSLForm form) {
        // Finding P5-05: a GET of the CCDL route is the web equivalent of a fresh CICS transaction
        // start (COBOL EIBCALEN = 0), so re-seat CDEMO-PGM-ENTER before delegating - exactly as the
        // sibling card-update GET does (finding #4). This converges CardDetailService.mainEntry on
        // the first-entry arm (a clean search prompt, or the card-list hand-off auto-fetch when a
        // selection is carried - that arm keys on CDEMO-PGM-ENTER + FROM = COCRDLIC + a live
        // selection, all still on the session context) instead of the stale re-enter arm that runs
        // 2000-PROCESS-INPUTS on the empty form and surfaces a stale red validation line. Genuine
        // input processing is preserved for form submissions, which arrive through the POST handler.
        context.markEnter();
        return processDetail(form, PfKey.ENTER);
    }

    /**
     * Handles a submission from the card-detail screen - the {@code CCDL} / {@code COCRDSLC}
     * re-entry path (COBOL {@code 2000-PROCESS-INPUTS} &rarr; {@code 9000-READ-DATA}).
     *
     * <p>Resolves the pressed key and delegates to {@link CardDetailService#mainEntry}. Supported
     * keys are ENTER (validate the account/card criteria and re-read for display) and PF3 (exit
     * back to the from-program or the main menu); the service remaps any other key to ENTER. A
     * missing record raises {@code RecordNotFoundException} in the service and is deliberately
     * <em>not</em> caught here, so it propagates to {@code GlobalExceptionHandler} (AAP
     * &sect;0.6.5).</p>
     *
     * @param form  the submitted card-detail form, bound as the {@code form} model attribute
     * @param pfkey the PF-key token submitted by the screen (defaults to ENTER)
     * @return {@link #VIEW_DETAIL}, or a {@code redirect:} to the from-program/menu route
     */
    @PostMapping(ROUTE_DETAIL)
    public String submitDetail(@Valid @ModelAttribute("form") COCRDSLForm form,
                               BindingResult bindingResult,
                               @RequestParam(name = PARAM_PFKEY, required = false,
                                       defaultValue = ENTER_TOKEN) String pfkey) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the detail header with a neutral banner
        // and performs no lookup, so the COBOL card-detail edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            populateHeaderDetail(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            return VIEW_DETAIL;
        }
        return processDetail(form, resolvePfKey(pfkey));
    }

    /**
     * Shared card-detail flow backing both the GET (display) and POST (submit) entry points.
     *
     * <p>Populates the fixed header, then delegates to {@link CardDetailService#mainEntry}. On a
     * {@link CardDetailService.RoutingAction#REDIRECT} outcome (the COBOL PF3 {@code XCTL}) it
     * mirrors the transfer with a {@code redirect:}; otherwise it returns {@link #VIEW_DETAIL},
     * because the service itself fills the body fields and the info/error lines during its
     * {@code 1000-SEND-MAP} step ({@code buildShowResult}).</p>
     *
     * @param form the card-detail form to populate
     * @param key  the resolved attention-id
     * @return {@link #VIEW_DETAIL}, or a {@code redirect:} route
     */
    private String processDetail(COCRDSLForm form, PfKey key) {
        populateHeaderDetail(form);
        CardDetailResult result = cardDetailService.mainEntry(form, key);
        if (result.action() == CardDetailService.RoutingAction.REDIRECT) {
            return redirectFor(context.getToProgram());
        }
        return VIEW_DETAIL;
    }

    // ========================================================================
    // CCUP - card update (COCRDUPC).
    // ========================================================================

    /**
     * Displays the card-update screen on initial entry - the {@code CCUP} / {@code COCRDUPC}
     * fresh-entry path (COBOL {@code 0000-MAIN} with {@code EIBCALEN = 0}).
     *
     * <p>Delegates to {@link CardUpdateService#mainEntry} with an ENTER attention-id; on a fresh
     * entry the service prompts for the search keys, and when the operator arrived from the card
     * list it fetches the selected card ready for update.</p>
     *
     * <p><b>Finding #4 (GET first-entry parity).</b> On the mainframe a fresh {@code CCUP} start
     * arrives with {@code EIBCALEN = 0} and the program {@code INITIALIZE}s working storage, sets
     * {@code CDEMO-PGM-ENTER} and {@code CCUP-DETAILS-NOT-FETCHED}, and renders the empty search
     * prompt. A browser GET is that pseudo-conversational first SEND. The only origin that carries a
     * pre-selected card into this screen is the card list ({@code COCRDLIC}), which {@code XCTL}s
     * with {@code CDEMO-CARD-NUM} set so the update screen auto-fetches it (service Branch 2). Every
     * <em>other</em> GET &mdash; a cold/bookmarked navigation, a browser refresh after leaving the
     * flow, the menu, or a re-navigation while the shared session context still names a different
     * from-program &mdash; must render the clean prompt rather than re-run the input edits on the
     * empty form (which would surface a stale red error line). Because the {@link CardDemoContext}
     * is session-scoped and long-lived (it is not recreated per screen the way a CICS COMMAREA is on
     * a fresh start), we reproduce {@code EIBCALEN = 0} here by re-seating the program-enter posture
     * unless this GET is the card-list hand-off, so {@code mainEntry} converges on the fresh-entry
     * branch (Branch 3) with no carried-over message.</p>
     *
     * @param form    the card-update form, bound as the {@code form} model attribute
     * @param session the HTTP session carrying the update snapshot (COMMAREA carrier)
     * @param model   the view model (carries the review-finding-#10 {@code confirmMode} flag)
     * @return the logical view name {@link #VIEW_UPDATE}
     */
    @GetMapping(ROUTE_UPDATE)
    public String displayUpdate(@ModelAttribute("form") COCRDUPForm form, HttpSession session,
                                Model model) {
        // Finding #4: a GET of the CCUP route is the web-tier equivalent of a fresh CICS
        // transaction start (COBOL EIBCALEN = 0), so unconditionally re-seat CDEMO-PGM-ENTER and
        // reinitialize the program-private update state before delegating. This mirrors COBOL,
        // where every entry begins PGM-ENTER + DETAILS-NOT-FETCHED and the service's EVALUATE then
        // selects the arm. The card-list hand-off still auto-fetches its selected card because
        // Branch 2 keys on CDEMO-PGM-ENTER + FROM = COCRDLIC + a live selection (all still carried
        // on the session context - initialize() resets only the CCUP-private state, never the
        // context selection) and re-seating merely guarantees PGM-ENTER, which the hand-off needs
        // anyway. Every other GET - the menu hand-off, a direct/bookmarked navigation, or a stale
        // re-enter left over from a prior interaction (e.g. list -> select -> update -> F3 -> list
        // -> re-GET, where CDEMO-FROM-PROGRAM stays COCRDLIC but the context is in re-enter state)
        // - converges on the clean search prompt (Branch 3) instead of falling through to input
        // processing (Branch 5) on the empty form, which previously surfaced a stale red
        // validation line such as "Card name not provided". Genuine input processing is preserved
        // for form submissions, which arrive through the POST handler (submitUpdate), never here.
        getOrCreateUpdateState(session).initialize();
        context.markEnter();
        return processUpdate(form, PfKey.ENTER, session, model);
    }

    /**
     * Handles a submission from the card-update screen - the {@code CCUP} / {@code COCRDUPC}
     * re-entry path (COBOL {@code 1000-PROCESS-INPUTS} &rarr; {@code 2000-DECIDE-ACTION} &rarr;
     * {@code 9000-READ-DATA}/{@code 9200-WRITE-PROCESSING}).
     *
     * <p>Resolves the pressed key and delegates to {@link CardUpdateService#mainEntry}. Supported
     * keys are ENTER (edit/validate the changed fields), PF5 (save - honored by the service only
     * in the {@code CCUP-CHANGES-OK-NOT-CONFIRMED} state), PF12 (re-fetch - honored only once the
     * details have been fetched) and PF3 (exit); the service remaps any other key, or an
     * out-of-state PF5/PF12, to ENTER. Optimistic-lock and logic exceptions raised during the
     * read-update-rewrite are not caught here and propagate to {@code GlobalExceptionHandler}
     * (AAP &sect;0.6.5).</p>
     *
     * @param form    the submitted card-update form, bound as the {@code form} model attribute
     * @param pfkey   the PF-key token submitted by the screen (defaults to ENTER)
     * @param session the HTTP session carrying the update snapshot (COMMAREA carrier)
     * @param model   the view model (carries the review-finding-#10 {@code confirmMode} flag)
     * @return {@link #VIEW_UPDATE}, or a {@code redirect:} to the from-program/menu route
     */
    @PostMapping(ROUTE_UPDATE)
    public String submitUpdate(@Valid @ModelAttribute("form") COCRDUPForm form,
                               BindingResult bindingResult,
                               @RequestParam(name = PARAM_PFKEY, required = false,
                                       defaultValue = ENTER_TOKEN) String pfkey,
                               HttpSession session, Model model) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the screen (not confirm mode, no armed
        // token) with a neutral banner and performs no update, so the COBOL card-update edit
        // ordering owned by CardUpdateService is untouched.
        if (bindingResult.hasErrors()) {
            populateHeaderUpdate(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            model.addAttribute(MODEL_ATTR_CONFIRM_MODE, false);
            form.setConfirmToken(null);
            return VIEW_UPDATE;
        }
        return processUpdate(form, resolvePfKey(pfkey), session, model);
    }

    /**
     * Shared card-update flow backing both the GET (display) and POST (submit) entry points.
     *
     * <p>Resolves the session update snapshot (COBOL {@code WS-THIS-PROGCOMMAREA}), publishes the
     * resolved attention-id onto a fresh {@link CardWorkArea} (the service reads it back through
     * {@link CardWorkArea#getPfKey()}), and delegates to {@link CardUpdateService#mainEntry}. On a
     * {@link CardUpdateService.RoutingAction#REDIRECT} outcome it mirrors the COBOL {@code XCTL};
     * otherwise it renders the screen via {@link #renderUpdate}, since the entire update body and
     * message lines are controller-owned (COBOL {@code 3200-SETUP-SCREEN-VARS} /
     * {@code 3250-SETUP-INFOMSG}).</p>
     *
     * @param form    the card-update form to edit and populate
     * @param key     the resolved attention-id
     * @param session the HTTP session carrying the update snapshot
     * @param model   the view model (carries the review-finding-#10 {@code confirmMode} flag)
     * @return {@link #VIEW_UPDATE}, or a {@code redirect:} route
     */
    private String processUpdate(COCRDUPForm form, PfKey key, HttpSession session, Model model) {
        CardUpdateState state = getOrCreateUpdateState(session);
        CardWorkArea work = new CardWorkArea();
        // CardWorkArea.getPfKey() = PfKey.fromAid(aid), whose lookup keys are the enum names;
        // publishing key.name() therefore round-trips exactly back to the resolved PfKey.
        work.setAid(key.name());
        CardUpdateResult result = cardUpdateService.mainEntry(form, work, state);
        if (result.getAction() == CardUpdateService.RoutingAction.REDIRECT) {
            return redirectFor(context.getToProgram());
        }
        return renderUpdate(form, work, state, result, model);
    }

    /**
     * Populates and returns the card-update view - the controller-owned COBOL
     * {@code 3200-SETUP-SCREEN-VARS} and {@code 3250-SETUP-INFOMSG} (the update service writes
     * nothing to the form).
     *
     * <p>{@code 3200} reproduces the COBOL exactly: on a program-enter render the search fields
     * are left cleared ({@code IF CDEMO-PGM-ENTER -> CONTINUE}); otherwise the account/card
     * search keys are echoed from the {@link CardWorkArea} (blanked when zero/absent), and the
     * card-detail fields are driven by the change-action state - blank when the details have not
     * been fetched, the {@code CCUP-OLD-*} snapshot while showing details, and the
     * {@code CCUP-NEW-*} values (with the old expiry day, which is not user-editable) once changes
     * have been entered. {@code 3250} selects the information line for the current state and the
     * error line is taken from the service edit state.</p>
     *
     * @param form   the form whose body and message lines are set
     * @param work   the work area supplying the echoed account/card search keys
     * @param state  the update state supplying the change-action and old/new snapshots
     * @param result the service result supplying the program-enter flag and the return message
     * @param model  the view model (carries the review-finding-#10 {@code confirmMode} flag)
     * @return the logical view name {@link #VIEW_UPDATE}
     */
    private String renderUpdate(COCRDUPForm form, CardWorkArea work, CardUpdateState state,
                                CardUpdateResult result, Model model) {
        populateHeaderUpdate(form);

        // ---- 3200-SETUP-SCREEN-VARS ----
        if (result.isProgramEnter()) {
            // IF CDEMO-PGM-ENTER -> CONTINUE: leave the search/detail fields blank.
            form.setAcctsid(null);
            form.setCardsid(null);
            form.setCrdname(null);
            form.setCrdstcd(null);
            form.setExpday(null);
            form.setExpmon(null);
            form.setExpyear(null);
        } else {
            // Echo the account/card search keys (LOW-VALUES when zero/absent).
            Long acctNumeric = work.getAcctIdNumeric();
            form.setAcctsid((acctNumeric == null || acctNumeric == 0L) ? null : work.getAcctId());
            Long cardNumeric = work.getCardNumNumeric();
            form.setCardsid((cardNumeric == null || cardNumeric == 0L) ? null : work.getCardNum());

            if (state.isDetailsNotFetched()) {
                // WHEN CCUP-DETAILS-NOT-FETCHED -> blank the detail fields.
                form.setCrdname(null);
                form.setCrdstcd(null);
                form.setExpday(null);
                form.setExpmon(null);
                form.setExpyear(null);
            } else if (state.isShowDetails()) {
                // WHEN CCUP-SHOW-DETAILS -> the fetched (old) values.
                form.setCrdname(state.getOldCrdName());
                form.setCrdstcd(state.getOldCrdStcd());
                form.setExpday(state.getOldExpDay());
                form.setExpmon(state.getOldExpMon());
                form.setExpyear(state.getOldExpYear());
            } else if (state.isChangesMade()) {
                // WHEN CCUP-CHANGES-MADE -> the new values, keeping the old (non-editable) day.
                form.setCrdname(state.getNewCrdName());
                form.setCrdstcd(state.getNewCrdStcd());
                form.setExpmon(state.getNewExpMon());
                form.setExpyear(state.getNewExpYear());
                form.setExpday(state.getOldExpDay());
            } else {
                // WHEN OTHER -> the old values.
                form.setCrdname(state.getOldCrdName());
                form.setCrdstcd(state.getOldCrdStcd());
                form.setExpday(state.getOldExpDay());
                form.setExpmon(state.getOldExpMon());
                form.setExpyear(state.getOldExpYear());
            }
        }

        // ---- 3250-SETUP-INFOMSG ----
        form.setInfomsg(resolveUpdateInfoMsg(result, state));   // MOVE WS-INFO-MSG TO INFOMSGO.
        form.setErrmsg(result.getEditState().getReturnMessage()); // MOVE WS-RETURN-MSG TO ERRMSGO.

        // Review finding #10: the confirmation window (read-only fields + single-use
        // token echoed to the hidden field) is open only while the state is
        // CCUP-CHANGES-OK-NOT-CONFIRMED; every other state clears the token. The token
        // is read from the session state that mainEntry() mutated in place.
        model.addAttribute(MODEL_ATTR_CONFIRM_MODE, state.isChangesOkNotConfirmed());
        form.setConfirmToken(state.getConfirmToken());
        return VIEW_UPDATE;
    }

    /**
     * Selects the card-update information line for the current state - the COBOL
     * {@code 3250-SETUP-INFOMSG} {@code EVALUATE TRUE}.
     *
     * <p>A program-enter render always prompts for the search keys; otherwise the message is
     * chosen from the change-action state, covering every {@code CCUP-*} 88-level.</p>
     *
     * @param result the service result supplying the program-enter flag
     * @param state  the update state supplying the change-action
     * @return the information line to display
     */
    private static String resolveUpdateInfoMsg(CardUpdateResult result, CardUpdateState state) {
        if (result.isProgramEnter()) {
            return INFO_PROMPT_FOR_SEARCH_KEYS;   // WHEN CDEMO-PGM-ENTER.
        }
        return switch (state.getChangeAction()) {
            case DETAILS_NOT_FETCHED -> INFO_PROMPT_FOR_SEARCH_KEYS;
            case SHOW_DETAILS -> INFO_FOUND_CARDS;
            case CHANGES_NOT_OK -> INFO_PROMPT_FOR_CHANGES;
            case CHANGES_OK_NOT_CONFIRMED -> INFO_PROMPT_FOR_CONFIRMATION;
            case CHANGES_OKAYED_AND_DONE -> INFO_CONFIRM_SUCCESS;
            case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> INFO_FAILURE;
        };
    }

    // ========================================================================
    // Shared helpers.
    // ========================================================================

    /**
     * Resolves the web PF-key token to the shared {@link PfKey}, routing through
     * {@link PfKeyHandler} exactly as the COBOL {@code EVALUATE EIBAID} did.
     *
     * <p>The card Thymeleaf forms submit the human-facing labels {@code "ENTER"} and
     * {@code "PFnn"} (mirroring the 3270 keyboard), whereas {@link PfKeyHandler#fromAid(String)}
     * recognizes CICS AID mnemonics ({@code DFHENTER}, {@code DFHPFnn}); this helper bridges the
     * two so PF-key resolution stays centralized in {@link PfKeyHandler}. A {@code null}, blank or
     * unrecognized token yields {@link PfKey#OTHER} (which every card service then coerces to
     * ENTER); the lookup is upper-cased with {@link Locale#ROOT} so it never varies with the
     * default locale. This mirrors the sibling menu controller so the whole web tier resolves
     * keys identically.</p>
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
        if (ENTER_TOKEN.equals(token)) {
            return PfKeyHandler.fromAid(AID_ENTER);
        }
        if (token.startsWith(PF_TOKEN_PREFIX)) {
            return PfKeyHandler.fromAid(AID_PF_PREFIX + token.substring(PF_TOKEN_PREFIX.length()));
        }
        return PfKey.OTHER;
    }

    /**
     * Maps the COBOL {@code XCTL} target program name recorded on the {@link CardDemoContext} to
     * its web route and prefixes it for a Spring MVC {@code redirect:}.
     *
     * <p>Every card service sets {@code toProgram}/{@code toTranid} on a redirect outcome; an
     * unknown or absent program name falls back to the main menu, matching the COBOL convention
     * of returning to the menu when the caller is blank.</p>
     *
     * @param toProgram the target program name (may be {@code null})
     * @return a {@code redirect:} view string for the resolved route
     */
    private static String redirectFor(String toProgram) {
        String route = (toProgram == null)
                ? ROUTE_MENU
                : PROGRAM_ROUTES.getOrDefault(toProgram, ROUTE_MENU);
        return REDIRECT_PREFIX + route;
    }

    /**
     * Populates the fixed card-list header - the header half of the COBOL {@code 1000-SEND-MAP}
     * ({@code POPULATE-HEADER-INFO}).
     *
     * @param form the form whose header fields are set
     */
    private void populateHeaderList(COCRDLIForm form) {
        form.setTrnname(TRAN_LIST);
        form.setPgmname(PGM_LIST);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the fixed card-detail header - the header half of the COBOL {@code 1000-SEND-MAP}
     * ({@code POPULATE-HEADER-INFO}).
     *
     * @param form the form whose header fields are set
     */
    private void populateHeaderDetail(COCRDSLForm form) {
        form.setTrnname(TRAN_DETAIL);
        form.setPgmname(PGM_DETAIL);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the fixed card-update header - the header portion of the COBOL
     * {@code 3000-SEND-MAP} ({@code 3100-SCREEN-INIT}).
     *
     * @param form the form whose header fields are set
     */
    private void populateHeaderUpdate(COCRDUPForm form) {
        form.setTrnname(TRAN_UPDATE);
        form.setPgmname(PGM_UPDATE);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Restricts request-parameter binding to the fields each card screen actually submits (review
     * finding #11), switching on the bound form type since all three screens share the {@code form}
     * model attribute. The list screen submits the two search keys plus the per-row selectors; the
     * detail screen submits only its two search keys; the update screen submits its editable card
     * fields plus the single-use confirmation token ({@code confirmToken}, finding #10). Display-only
     * header/title/date/message fields are excluded so they can no longer be over-posted;
     * {@code pfkey} arrives as a {@code @RequestParam} and is not bound through the form.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget() is null
        // when @InitBinder runs; the resolved binder.getTargetType() is the reliable discriminator
        // (it is null for simple @RequestParam binders such as pfkey).
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (COCRDLIForm.class.equals(targetType)) {
            binder.setAllowedFields(
                    "acctsid", "cardsid",
                    "crdsel1", "crdsel2", "crdsel3", "crdsel4", "crdsel5", "crdsel6", "crdsel7",
                    "crdstp2", "crdstp3", "crdstp4", "crdstp5", "crdstp6", "crdstp7");
        } else if (COCRDSLForm.class.equals(targetType)) {
            binder.setAllowedFields("acctsid", "cardsid");
        } else if (COCRDUPForm.class.equals(targetType)) {
            binder.setAllowedFields(
                    "acctsid", "cardsid", "crdname", "crdstcd",
                    "expmon", "expday", "expyear", "confirmToken");
        }
    }

    /**
     * Returns the session-held card-list paging state, creating and registering a fresh instance
     * on first use. This is the web-tier home of the COBOL card-list {@code WS-THIS-PROGCOMMAREA}
     * that carried the keyset cursor and the seven persisted rows across pseudo-conversational
     * turns.
     *
     * @param session the current HTTP session
     * @return the (possibly newly created) paging state
     */
    private static CardListPagingState getOrCreatePagingState(HttpSession session) {
        Object existing = session.getAttribute(SESSION_PAGING_STATE);
        if (existing instanceof CardListPagingState paging) {
            return paging;
        }
        CardListPagingState paging = new CardListPagingState();
        session.setAttribute(SESSION_PAGING_STATE, paging);
        return paging;
    }

    /**
     * Returns the session-held card-update state, creating and registering a fresh instance on
     * first use. This is the web-tier home of the COBOL card-update {@code WS-THIS-PROGCOMMAREA}
     * that carried the change-action flag and the {@code CCUP-OLD-*}/{@code CCUP-NEW-*} snapshots
     * (the optimistic-lock basis) across pseudo-conversational turns.
     *
     * @param session the current HTTP session
     * @return the (possibly newly created) update state
     */
    private static CardUpdateState getOrCreateUpdateState(HttpSession session) {
        Object existing = session.getAttribute(SESSION_UPDATE_STATE);
        if (existing instanceof CardUpdateState state) {
            return state;
        }
        CardUpdateState state = new CardUpdateState();
        session.setAttribute(SESSION_UPDATE_STATE, state);
        return state;
    }
}
