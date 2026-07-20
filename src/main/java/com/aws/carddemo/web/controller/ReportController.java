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
import com.aws.carddemo.dto.screen.CORPT00Form;
import com.aws.carddemo.service.online.ReportSubmitService;
import com.aws.carddemo.service.online.ReportSubmitService.AidKey;
import com.aws.carddemo.service.online.ReportSubmitService.ReportSubmitResult;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.ScreenTitles;
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
 * Spring MVC controller for the AWS CardDemo online transaction-report submission screen.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/CORPT00C.cbl} (CICS tran {@code CR00}, mapset {@code CORPT00}).
 * Report submission; launches the Spring Batch transaction-report job via {@link ReportSubmitService}
 * (replaces the COBOL TDQ/JCL submission - no new interface).</p>
 *
 * <p>This class is the web-tier replacement for the CICS transaction {@code CR00} whose program is
 * {@code CORPT00C} (verified in {@code legacy/csd/CARDDEMO.CSD}:
 * {@code DEFINE TRANSACTION(CR00) ... PROGRAM(CORPT00C)}). It renders the report screen
 * ({@code CORPT0A} of mapset {@code CORPT00}, view {@code CORPT00}), accepts a report type
 * (Monthly / Yearly / Custom) with - for a custom report - a start/end date range, and drives the
 * transaction-report batch submission. The COBOL {@code XCTL PROGRAM(...)} of
 * {@code RETURN-TO-PREV-SCREEN} is mirrored by a Spring MVC {@code redirect:} to the target
 * program's route (AAP &sect;0.3.3, &sect;0.4.1).</p>
 *
 * <h2>Batch launch replaces the COBOL TDQ/JCL submission (no new interface)</h2>
 * <p>{@code CORPT00C} submits batch JCL by writing to the extra-partition transient-data queue
 * {@code JOBS} ({@code DEFINE TDQUEUE(JOBS)} in {@code legacy/csd/CARDDEMO.CSD}) / the JES2 internal
 * reader. In the modernized architecture that submission becomes a launch of the <b>Spring Batch</b>
 * transaction-report job - <b>no JCL, no TDQ, no new external interface</b> (AAP &sect;0.6.4 forbids
 * new interfaces; &sect;0.6.3 maps JCL &rarr; Spring Batch). The crucial layering rule is that the
 * {@code JobLauncher.run(...)} call and the {@code batch.TransactionReportJobConfig} {@code Job} bean
 * are owned by {@link ReportSubmitService} (which constructor-injects the {@code JobLauncher} and the
 * {@code transactionReportJob} bean and performs {@code SUBMIT-JOB-TO-INTRDR}). This controller
 * therefore delegates to {@link ReportSubmitService} and <b>never imports {@code batch/} or a
 * {@code JobLauncher}</b>; the web tier stays thin and the no-new-interface guarantee is preserved
 * (controller &rarr; service &rarr; batch).</p>
 *
 * <h2>Presentation-only responsibility</h2>
 * <p>This controller performs presentation and navigation only. All business logic - report-type
 * determination, the Monthly/Yearly/Custom date-window computation, the parity-sensitive date
 * validation ({@code CSUTLDTC} &rarr; {@code DateConversionService}, AAP &sect;0.6.9), the confirm
 * ({@code Y}/{@code N}) gating, the {@code JobParameters} assembly and the batch launch - lives in
 * {@link ReportSubmitService} (the migration of the {@code CORPT00C} business paragraphs). No
 * repository is touched, no date math is performed, and no monetary arithmetic occurs here, so this
 * class declares no {@code BigDecimal} and never uses {@code float}/{@code double}. The COBOL
 * paragraphs map as follows:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} (the {@code EIBCALEN}/{@code CDEMO-PGM-REENTER} branch plus
 *       {@code EVALUATE EIBAID}) &rarr; {@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)},
 *       reached from both {@link #showReport(CORPT00Form)} and
 *       {@link #submitReport(CORPT00Form, String)}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} + {@code SUBMIT-JOB-TO-INTRDR} &rarr; delegated inside
 *       {@code mainEntry} to {@link ReportSubmitService#processEnterKey(CORPT00Form)} on the
 *       {@code ENTER} key.</li>
 *   <li>{@code RECEIVE-TRNRPT-SCREEN} &rarr; Spring form binding of {@link CORPT00Form} on
 *       {@code POST /report}.</li>
 *   <li>{@code SEND-TRNRPT-SCREEN} / {@code POPULATE-HEADER-INFO} &rarr;
 *       {@link #populateHeader(CORPT00Form)} plus the {@code CORPT00} view render.</li>
 *   <li>The {@code EVALUATE EIBAID} key resolution &rarr; {@link #resolvePfKey(String)} and
 *       {@link #toAidKey(PfKey)}.</li>
 * </ul>
 *
 * <h2>Delegation contract</h2>
 * <p>{@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)} is the service's documented
 * controller-facing entry point: it owns the whole {@code MAIN-PARA} control flow (first-entry
 * detection, the empty first-display, and the {@code ENTER}/{@code PF3}/other dispatch, including the
 * private {@code RETURN-TO-PREV-SCREEN} hand-off). This controller therefore resolves the pressed key
 * to an {@link AidKey}, delegates to {@code mainEntry}, and interprets the returned
 * {@link ReportSubmitResult}: a redirect outcome (the COBOL {@code XCTL}) is mapped to a
 * {@code redirect:} for the named program, while a redisplay outcome re-renders the {@code CORPT00}
 * screen with the (green success / neutral / error) message on the {@code ERRMSG} line. The
 * invalid-key message for the COBOL {@code WHEN OTHER} branch ({@code CCDA-MSG-INVALID-KEY}) is
 * produced by the service; consistent with the sibling service-backed controllers this class does
 * not itself reference {@code util.constants.Messages}.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The CICS {@code COMMAREA} ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}, shared with {@link ReportSubmitService} so both observe the same
 * per-session navigation state. First entry ({@code EIBCALEN = 0}), the {@code CDEMO-PGM-CONTEXT}
 * enter/re-enter flag ({@code 0}/{@code 1}), and the {@code from*}/{@code to*} hand-off are all
 * handled inside {@code mainEntry}; this controller only records the target transaction id on the
 * context before a redirect.</p>
 *
 * <h2>No feature expansion (AAP &sect;0.2.2)</h2>
 * <p>{@code CORPT00C} offers exactly three report types (Monthly, Yearly, Custom). This controller
 * introduces no new report type, no new input beyond the fields carried on {@link CORPT00Form}, and
 * no REST/JSON surface.</p>
 *
 * <h2>Exception handling (AAP &sect;0.6.5)</h2>
 * <p>Typed exceptions raised by the service tier are deliberately <em>not</em> caught here; they
 * propagate to {@code com.aws.carddemo.exception.GlobalExceptionHandler}.</p>
 *
 * <h2>Program &rarr; route mapping</h2>
 * <table border="1">
 *   <caption>Redirect target program &rarr; tran id / route (COBOL {@code XCTL} targets)</caption>
 *   <tr><th>Program</th><th>Tran</th><th>Route</th><th>Source</th></tr>
 *   <tr><td>COSGN00C</td><td>CC00</td><td>/signon</td><td>first entry / default</td></tr>
 *   <tr><td>COMEN01C</td><td>CM00</td><td>/menu</td><td>PF3 back</td></tr>
 * </table>
 *
 * <p>Constructor dependency injection, {@code private final} collaborators, no Lombok, no field
 * injection; a {@link Controller} (never {@code @RestController}) returning logical Thymeleaf view
 * names, so no REST/JSON surface is introduced (AAP &sect;0.3.4). Compiles warning-free under
 * {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see ReportSubmitService
 * @see CardDemoContext
 * @see CORPT00Form
 */
@Controller
public class ReportController {

    /** Logical Thymeleaf view name; resolves to {@code templates/CORPT00.html}. */
    private static final String VIEW_REPORT = "CORPT00";

    /**
     * Neutral banner shown when a submitted field exceeds its physical BMS width (review finding
     * #11). A well-behaved 3270/Thymeleaf client can never trigger this - every input carries a
     * {@code maxlength} matching its {@code @Size} constraint - so it is reachable only by a crafted
     * request that bypasses the screen. It therefore never displaces a COBOL business-edit message,
     * preserving {@code CORPT00C}'s message ordering.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    /** Web route for this screen (COBOL tran {@code CR00}); GET displays, POST submits. */
    private static final String PATH_REPORT = "/report";

    /** COBOL {@code WS-TRANID VALUE 'CR00'} - this screen's CICS transaction id. */
    private static final String TRANSACTION_ID = "CR00";

    /** COBOL {@code WS-PGMNAME VALUE 'CORPT00C'} - this screen's program name. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** Spring MVC redirect prefix used to mirror the COBOL {@code XCTL}. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Model attribute name bound by the {@code CORPT00} template ({@code th:object="${form}"}). */
    private static final String MODEL_ATTR_FORM = "form";

    /** Request-parameter name carrying the activated PF-key from the {@code CORPT00} form. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Web PF-key token for the ENTER action (see {@code CORPT00.html} submit button). */
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
     * Fallback redirect target - the main menu ({@code COMEN01C} / {@code CM00} / {@code /menu}).
     * Used when {@code mainEntry} hands off to an unmapped program name, reproducing the COBOL
     * {@code RETURN-TO-PREV-SCREEN} default of routing to the main menu when the originating program
     * is unknown.
     */
    private static final Target DEFAULT_TARGET = new Target(MENU_TRANID, ROUTE_MENU);

    /**
     * Authoritative mapping from the COBOL {@code XCTL} target program name (recorded by
     * {@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)} on
     * {@link CardDemoContext#getToProgram()}) to its web {@link Target} (transaction id + route). The
     * map is immutable and never exposed, so it introduces no shared mutable state. Only the two
     * programs {@code CORPT00C} can hand off to are listed: the sign-on screen (first entry /
     * default) and the main menu (PF3 back).
     */
    private static final Map<String, Target> PROGRAM_ROUTES = Map.of(
            SIGNON_PROGRAM, new Target(SIGNON_TRANID, ROUTE_SIGNON),
            MENU_PROGRAM, DEFAULT_TARGET);

    /**
     * Report-submission business service (migration of {@code CORPT00C} paragraphs). Owns the
     * report-type determination, the date-window computation and validation, the {@code JobParameters}
     * assembly and the Spring Batch job launch, and the whole {@code MAIN-PARA} control flow; this
     * controller only orchestrates presentation.
     */
    private final ReportSubmitService reportSubmitService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a scoped proxy and
     * shared with {@link ReportSubmitService}, so both observe the same per-session navigation state.
     */
    private final CardDemoContext context;

    /**
     * Creates the report controller with its injected collaborators.
     *
     * <p>The constructor only stores the two references and invokes no overridable method, so it is
     * free of the {@code this-escape} lint category under the zero-warning build.</p>
     *
     * @param reportSubmitService the report-submission business service; must not be {@code null}
     * @param context             the session-scoped {@link CardDemoContext} (COMMAREA replacement);
     *                            must not be {@code null}
     */
    public ReportController(ReportSubmitService reportSubmitService, CardDemoContext context) {
        this.reportSubmitService = reportSubmitService;
        this.context = context;
    }

    /**
     * Displays the report screen - the first-display path of CICS tran {@code CR00} / program
     * {@code CORPT00C}.
     *
     * <p>Reproduces the COBOL {@code MAIN-PARA} entry with no attention key pressed. Delegation is to
     * {@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)} with a {@code null} AID: because
     * {@code mainEntry} owns the {@code EIBCALEN}/{@code CDEMO-PGM-REENTER} state machine, it decides
     * between bouncing to sign-on on a cold entry ({@code EIBCALEN = 0}) and performing the empty
     * first-display initialization ({@code MOVE LOW-VALUES TO CORPT0AO}, {@code PERFORM
     * SEND-TRNRPT-SCREEN}, with no pre-processing). A {@code null} AID is treated as the COBOL
     * {@code WHEN OTHER} default and is never evaluated on the first-display branch, so a fresh
     * display never yields the invalid-key message.</p>
     *
     * @param form the report screen form, bound under the model attribute {@code form} so the
     *             {@code CORPT00} template can render it
     * @return the logical view name {@link #VIEW_REPORT}, or a {@code redirect:} to sign-on on a cold
     *         first entry
     */
    @GetMapping(PATH_REPORT)
    public String showReport(@ModelAttribute(MODEL_ATTR_FORM) CORPT00Form form) {
        // COBOL MAIN-PARA first display: no AID pressed; the service entry point handles first-entry
        // detection and the empty first-display initialization.
        return handleInteraction(null, form);
    }

    /**
     * Handles a report submission - the {@code RECEIVE-TRNRPT-SCREEN} + {@code EVALUATE EIBAID} path
     * of CICS tran {@code CR00} / program {@code CORPT00C}.
     *
     * <p>The pressed key is resolved to a {@link PfKey} via {@link #resolvePfKey(String)} and mapped
     * to the service's {@link AidKey} via {@link #toAidKey(PfKey)}, then handed to
     * {@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)}, which reproduces the COBOL
     * {@code EVALUATE EIBAID}: {@code ENTER} runs {@code PROCESS-ENTER-KEY} (determine the report
     * type, compute/validate the date window, then on confirmation build the {@code JobParameters}
     * and launch the transaction-report batch job in {@code SUBMIT-JOB-TO-INTRDR}); {@code PF3} runs
     * {@code RETURN-TO-PREV-SCREEN} (the {@code XCTL} back to the main menu {@code COMEN01C}); any
     * other key yields the invalid-key message ({@code CCDA-MSG-INVALID-KEY}).</p>
     *
     * @param form  the bound report form (COBOL {@code CORPT0AI}); supplies the report-type flags,
     *              the custom start/end date parts and the confirmation, exposed under the model
     *              attribute {@code form}
     * @param pfkey the PF-key token submitted by the template ({@code "ENTER"} or {@code "PF3"}); a
     *              {@code null}/blank/unrecognized value resolves to the COBOL {@code WHEN OTHER}
     *              default
     * @return a {@code redirect:} to the target screen (COBOL {@code XCTL}), or the logical view name
     *         {@link #VIEW_REPORT} when the screen is re-displayed
     */
    @PostMapping(PATH_REPORT)
    public String submitReport(@Valid @ModelAttribute(MODEL_ATTR_FORM) CORPT00Form form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false) String pfkey) {
        // Finding #11: a field over its physical BMS width can only arrive from a crafted request;
        // reject it up front with a neutral banner and no service call, so CORPT00C's own edit
        // messages (which run for every in-width input) keep their exact COBOL ordering.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            return VIEW_REPORT;
        }
        AidKey aid = toAidKey(resolvePfKey(pfkey));
        return handleInteraction(aid, form);
    }

    /**
     * Delegates to the service entry point and maps its outcome to a redirect or a screen
     * re-display - the controller half of {@code MAIN-PARA}.
     *
     * <p>{@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)} returns a
     * {@link ReportSubmitResult} describing what to do next. A redirect outcome (the COBOL
     * {@code XCTL} of {@code RETURN-TO-PREV-SCREEN}) names the target program; this method looks it up
     * in {@link #PROGRAM_ROUTES} (defaulting to the main menu for an unmapped program, matching the
     * COBOL default), records the target transaction id on the context to preserve the hand-off, and
     * issues the {@code redirect:}. A redisplay outcome re-renders the {@code CORPT00} screen: the
     * fixed header is populated ({@code POPULATE-HEADER-INFO}) and the result's message (empty, the
     * green success line, or an error/prompt) is placed on the {@code ERRMSG} line
     * ({@code MOVE WS-MESSAGE TO ERRMSGO}); the report-type/date/confirm fields carried on the form
     * have already been set (or cleared) by the service.</p>
     *
     * @param aid  the resolved attention key, or {@code null} for a plain display (treated by the
     *             service as the COBOL {@code WHEN OTHER} default)
     * @param form the screen form to submit and, on a redisplay, to populate for rendering
     * @return a {@code redirect:} view name for a hand-off, or {@link #VIEW_REPORT} for a redisplay
     */
    private String handleInteraction(AidKey aid, CORPT00Form form) {
        ReportSubmitResult result = reportSubmitService.mainEntry(aid, form);
        if (result.isRedirect()) {
            // COBOL RETURN-TO-PREV-SCREEN: XCTL to the target program -> redirect to its route.
            Target target = PROGRAM_ROUTES.getOrDefault(result.targetProgram(), DEFAULT_TARGET);
            context.setToTranid(target.transactionId());
            return REDIRECT_PREFIX + target.route();
        }
        // COBOL SEND-TRNRPT-SCREEN: populate the header and the ERRMSG line, then render.
        populateHeader(form);
        form.setErrmsg(result.message());
        return VIEW_REPORT;
    }

    /**
     * Populates the fixed screen header - migration of {@code POPULATE-HEADER-INFO}
     * ({@code legacy/cbl/CORPT00C.cbl}).
     *
     * <p>Sets the transaction id ({@code WS-TRANID}), program name ({@code WS-PGMNAME}), the two
     * banner titles ({@code CCDA-TITLE01}/{@code CCDA-TITLE02}), and the current date/time rendered
     * with the COBOL {@code MM/DD/YY} and {@code HH:MM:SS} edit masks via {@link DateStruct}. The
     * report-type/date/confirm entry fields are owned by {@link ReportSubmitService} and are not
     * touched here.</p>
     *
     * @param form the form whose header fields are set
     */
    private void populateHeader(CORPT00Form form) {
        form.setTrnname(TRANSACTION_ID);
        form.setPgmname(PROGRAM_NAME);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Restricts request-parameter binding to the fields the {@code CORPT00} screen actually submits
     * (review finding #11): the report-type radios/flags ({@code monthly}, {@code yearly},
     * {@code custom}), the custom start/end date components
     * ({@code sdtdd}/{@code sdtmm}/{@code sdtyyyy}, {@code edtdd}/{@code edtmm}/{@code edtyyyy}) and
     * the confirmation flag ({@code confirm}). Display-only header/title/date/message fields are
     * excluded so they can no longer be over-posted; {@code pfkey} arrives as a
     * {@code @RequestParam} and is not bound through the form.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget() is null
        // when @InitBinder runs; the resolved binder.getTargetType() is the reliable discriminator.
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (CORPT00Form.class.equals(targetType)) {
            binder.setAllowedFields("monthly", "yearly", "custom",
                    "sdtdd", "sdtmm", "sdtyyyy",
                    "edtdd", "edtmm", "edtyyyy",
                    "confirm");
        }
    }

    /**
     * Resolves the web PF-key token to the shared {@link PfKey}, routing through
     * {@link PfKeyHandler} exactly as the COBOL {@code EVALUATE EIBAID} did.
     *
     * <p>The {@code CORPT00} Thymeleaf form submits the human-facing labels {@code "ENTER"} and
     * {@code "PF3"} in its {@code pfkey} parameter (mirroring the 3270 keyboard), whereas
     * {@link PfKeyHandler#fromAid(String)} recognizes CICS AID mnemonics ({@code DFHENTER},
     * {@code DFHPFnn}). This helper bridges the two by mapping the label to its AID mnemonic before
     * delegating, so PF-key resolution stays centralized in {@link PfKeyHandler}. A {@code null},
     * blank or unrecognized token yields {@link PfKey#OTHER}, matching the COBOL {@code WHEN OTHER}
     * branch; the lookup is upper-cased with {@link Locale#ROOT} so it never varies with the default
     * locale.</p>
     *
     * @param pfkey the raw PF-key token from the request (may be {@code null})
     * @return the resolved {@link PfKey}; {@link PfKey#OTHER} for {@code null}, blank or unrecognized
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
        if (WEB_KEY_ENTER.equals(token)) {
            return PfKeyHandler.fromAid(AID_ENTER);
        }
        if (token.startsWith(WEB_KEY_PF_PREFIX)) {
            return PfKeyHandler.fromAid(AID_PF_PREFIX + token.substring(WEB_KEY_PF_PREFIX.length()));
        }
        return PfKey.OTHER;
    }

    /**
     * Maps a resolved {@link PfKey} to the service's {@link AidKey}, honoring the two keys
     * {@code CORPT00C} acts on and collapsing everything else to the COBOL {@code WHEN OTHER}
     * default.
     *
     * <p>{@link PfKey#ENTER} &rarr; {@link AidKey#ENTER} (process/submit the report),
     * {@link PfKey#PFK03} &rarr; {@link AidKey#PF3} (back to the main menu); any other key -
     * including {@code CLEAR}, PA1/PA2 and PF1/2/4..12 - maps to {@link AidKey#OTHER}, which the
     * service surfaces as the invalid-key message on a re-entry. {@code CORPT00C} has no PF4/PF7/PF8
     * behavior, so no other key is mapped.</p>
     *
     * @param key the resolved PF-key; must not be {@code null}
     * @return the corresponding service {@link AidKey}
     */
    private static AidKey toAidKey(PfKey key) {
        return switch (key) {
            case ENTER -> AidKey.ENTER;
            case PFK03 -> AidKey.PF3;
            default -> AidKey.OTHER;
        };
    }

    /**
     * Immutable web target for a COBOL {@code XCTL} hand-off: the CICS transaction id to record on
     * the context and the Spring MVC route to redirect to.
     *
     * @param transactionId the target CICS transaction id (for example {@code CM00})
     * @param route         the target web route (for example {@code /menu})
     */
    private record Target(String transactionId, String route) {
    }
}
