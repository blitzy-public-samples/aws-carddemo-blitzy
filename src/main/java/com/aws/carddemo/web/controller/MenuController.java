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
import com.aws.carddemo.dto.screen.COMEN01Form;
import com.aws.carddemo.service.online.MainMenuService;
import com.aws.carddemo.service.online.MainMenuService.MainMenuResult;
import com.aws.carddemo.service.online.MainMenuService.RoutingAction;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.Messages;
import com.aws.carddemo.util.constants.ScreenTitles;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
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
 * Spring MVC controller for the AWS CardDemo main (regular-user) menu.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COMEN01C.cbl} (CICS tran CM00, mapset
 * COMEN01). Main user menu; option -&gt; redirect; PF3 -&gt; signon.</p>
 *
 * <p>This class is the web-tier replacement for the CICS transaction {@code CM00}
 * whose program is {@code COMEN01C} (verified in {@code legacy/csd/CARDDEMO.CSD}:
 * {@code DEFINE TRANSACTION(CM00) ... PROGRAM(COMEN01C)}). It renders the ten-option
 * main menu, accepts a numeric option selection, and mirrors the COBOL
 * {@code XCTL PROGRAM(...)} by issuing a Spring MVC {@code redirect:} to the
 * selected screen's route. {@code PF3} mirrors {@code XCTL COSGN00C} by returning
 * to the sign-on screen (AAP &sect;0.3.3, &sect;0.4.1).</p>
 *
 * <h2>Presentation-only responsibility</h2>
 * <p>This controller performs presentation and navigation only. All option
 * parsing, validation, the admin gate, and the option catalog live in
 * {@link MainMenuService} (the migration of the {@code COMEN01C} business
 * paragraphs) and {@code dto.menu.MainMenuOptions} (the migrated {@code COMEN02Y}
 * table). No repository or business logic is invoked here. The COBOL
 * presentation paragraphs map as follows:</p>
 * <ul>
 *   <li>{@code SEND-MENU-SCREEN} / {@code BUILD-MENU-OPTIONS} / {@code POPULATE-HEADER-INFO}
 *       &rarr; {@link #renderMenu(COMEN01Form, String)} (view render + header +
 *       option display slots).</li>
 *   <li>{@code RECEIVE-MENU-SCREEN} &rarr; Spring form binding of {@link COMEN01Form}
 *       on {@code POST /menu}.</li>
 *   <li>{@code EVALUATE EIBAID} &rarr; {@link #resolvePfKey(String)} plus the
 *       dispatch {@code switch} in {@link #handleMenu(COMEN01Form, String)}.</li>
 * </ul>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The CICS {@code COMMAREA} ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}. First entry ({@code EIBCALEN = 0}) is detected via
 * {@link CardDemoContext#isNew()} and, as in COBOL {@code MAIN-PARA} (lines 82-84),
 * bounces to the sign-on screen. The {@code CDEMO-PGM-CONTEXT} enter/re-enter flag
 * is preserved through {@link CardDemoContext#markReenter()} /
 * {@link CardDemoContext#markEnter()}, and the {@code from*}/{@code to*} hand-off is
 * set before every redirect so the arriving controller can reconstruct the
 * navigation state machine.</p>
 *
 * <h2>Option &rarr; route mapping</h2>
 * <p>The single authoritative option catalog is {@code MainMenuOptions}; the COBOL
 * {@code XCTL} target program name that {@link MainMenuService#processEnterKey} records
 * on the context is mapped here to its web route through the one private
 * {@link #PROGRAM_ROUTES} table, keeping routing traceable to
 * {@code MainMenuOptions} and {@code CARDDEMO.CSD}:</p>
 * <table border="1">
 *   <caption>Menu option &rarr; program / tran id / route (menu order preserved)</caption>
 *   <tr><th>#</th><th>Program</th><th>Tran</th><th>Route</th></tr>
 *   <tr><td>1</td><td>COACTVWC</td><td>CAVW</td><td>/account/view</td></tr>
 *   <tr><td>2</td><td>COACTUPC</td><td>CAUP</td><td>/account/update</td></tr>
 *   <tr><td>3</td><td>COCRDLIC</td><td>CCLI</td><td>/card/list</td></tr>
 *   <tr><td>4</td><td>COCRDSLC</td><td>CCDL</td><td>/card/detail</td></tr>
 *   <tr><td>5</td><td>COCRDUPC</td><td>CCUP</td><td>/card/update</td></tr>
 *   <tr><td>6</td><td>COTRN00C</td><td>CT00</td><td>/transaction/list</td></tr>
 *   <tr><td>7</td><td>COTRN01C</td><td>CT01</td><td>/transaction/view</td></tr>
 *   <tr><td>8</td><td>COTRN02C</td><td>CT02</td><td>/transaction/add</td></tr>
 *   <tr><td>9</td><td>CORPT00C</td><td>CR00</td><td>/report</td></tr>
 *   <tr><td>10</td><td>COBIL00C</td><td>CB00</td><td>/billpay</td></tr>
 * </table>
 *
 * <p>Constructor dependency injection, {@code private final} collaborators, no
 * Lombok, no field injection; a {@link Controller} (never {@code @RestController})
 * returning logical Thymeleaf view names, so no REST/JSON surface is introduced
 * (AAP &sect;0.3.4). Typed exceptions raised by the service tier are deliberately
 * not caught here; they propagate to {@code exception.GlobalExceptionHandler}.
 * Compiles warning-free under {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see MainMenuService
 * @see CardDemoContext
 * @see COMEN01Form
 */
@Controller
public class MenuController {

    /** Logical Thymeleaf view name; resolves to {@code templates/COMEN01.html}. */
    private static final String VIEW_MENU = "COMEN01";

    /**
     * Neutral banner shown when data binding rejects a field for exceeding its declared
     * {@code @Size} width (review finding #11). Only reachable by a crafted client (the template
     * {@code maxlength} / 3270 field width makes it impossible otherwise), so it carries no COBOL
     * business message and simply re-displays the menu without routing.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    /** COBOL {@code WS-TRANID VALUE 'CM00'} - this screen's CICS transaction id. */
    private static final String TRANSACTION_ID = "CM00";

    /** COBOL {@code WS-PGMNAME VALUE 'COMEN01C'} - this screen's program name. */
    private static final String PROGRAM_NAME = "COMEN01C";

    /** Sign-on program ({@code COSGN00C}); COBOL {@code XCTL} target on first entry / PF3. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Spring MVC redirect prefix used to mirror the COBOL {@code XCTL}. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Redirect to the sign-on route (mirrors {@code XCTL COSGN00C}). */
    private static final String SIGNON_REDIRECT = "redirect:/signon";

    /** Request-parameter name carrying the pressed PF-key from the COMEN01 form. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Web PF-key token for the ENTER action submitted by the COMEN01 template. */
    private static final String ENTER_TOKEN = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code PF3}). */
    private static final String PF_TOKEN_PREFIX = "PF";

    /** CICS AID mnemonic for ENTER, consumed by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix for program-function keys ({@code DFHPFnn}). */
    private static final String AID_PF_PREFIX = "DFHPF";

    /**
     * Authoritative mapping from the COBOL {@code XCTL} target program name to its
     * web {@link MenuTarget} (transaction id + route). Keyed by the program name
     * that {@link MainMenuService#processEnterKey(COMEN01Form, CardDemoContext)}
     * records on {@link CardDemoContext#getToProgram()} for a valid option. The map
     * is immutable and never exposed, so it introduces no shared mutable state.
     */
    private static final Map<String, MenuTarget> PROGRAM_ROUTES = Map.ofEntries(
            Map.entry("COACTVWC", new MenuTarget("CAVW", "/account/view")),
            Map.entry("COACTUPC", new MenuTarget("CAUP", "/account/update")),
            Map.entry("COCRDLIC", new MenuTarget("CCLI", "/card/list")),
            Map.entry("COCRDSLC", new MenuTarget("CCDL", "/card/detail")),
            Map.entry("COCRDUPC", new MenuTarget("CCUP", "/card/update")),
            Map.entry("COTRN00C", new MenuTarget("CT00", "/transaction/list")),
            Map.entry("COTRN01C", new MenuTarget("CT01", "/transaction/view")),
            Map.entry("COTRN02C", new MenuTarget("CT02", "/transaction/add")),
            Map.entry("CORPT00C", new MenuTarget("CR00", "/report")),
            Map.entry("COBIL00C", new MenuTarget("CB00", "/billpay")));

    /**
     * Regular-user main-menu business service (migration of {@code COMEN01C}
     * paragraphs). Performs option parsing/validation, the admin gate, and the
     * option display list; this controller only orchestrates presentation.
     */
    private final MainMenuService mainMenuService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a
     * scoped proxy and shared with {@link MainMenuService}, so both observe the same
     * per-session navigation and identity state.
     */
    private final CardDemoContext context;

    /**
     * Creates the menu controller with its injected collaborators.
     *
     * <p>The constructor only stores the two references and invokes no overridable
     * method, so it is free of the {@code this-escape} lint category under the
     * zero-warning build.</p>
     *
     * @param mainMenuService the main-menu business service; must not be {@code null}
     * @param context         the session-scoped {@link CardDemoContext}
     *                        (COMMAREA replacement); must not be {@code null}
     */
    public MenuController(MainMenuService mainMenuService, CardDemoContext context) {
        this.mainMenuService = mainMenuService;
        this.context = context;
    }

    /**
     * Displays the main menu - the {@code SEND-MENU-SCREEN} path of CICS tran
     * {@code CM00} / program {@code COMEN01C}.
     *
     * <p>Reproduces the first part of COBOL {@code MAIN-PARA}: when there is no
     * COMMAREA ({@code EIBCALEN = 0}, i.e. {@link CardDemoContext#isNew()}) the
     * screen bounces to sign-on (COBOL lines 82-84); otherwise it is the
     * {@code NOT CDEMO-PGM-REENTER} branch (lines 87-90) - the program flips to
     * re-enter ({@link CardDemoContext#markReenter()}) and a fresh menu is rendered
     * with its header and the ten option lines populated.</p>
     *
     * @param form the menu screen form, bound under the model attribute
     *             {@code form} so the {@code COMEN01} template can render it
     * @return the logical view name {@link #VIEW_MENU}, or a redirect to sign-on on
     *         first entry
     */
    @GetMapping("/menu")
    public String showMenu(@ModelAttribute("form") COMEN01Form form) {
        // COBOL MAIN-PARA lines 82-84: IF EIBCALEN = 0 -> no COMMAREA; bounce to sign-on.
        if (context.isNew()) {
            context.setFromProgram(SIGNON_PROGRAM);
            mainMenuService.returnToSignonScreen(context);
            return SIGNON_REDIRECT;
        }
        // COBOL MAIN-PARA lines 87-90: NOT CDEMO-PGM-REENTER -> fresh SEND-MENU-SCREEN.
        context.markReenter();
        renderMenu(form, null);
        return VIEW_MENU;
    }

    /**
     * Handles a menu submission - the {@code RECEIVE-MENU-SCREEN} +
     * {@code EVALUATE EIBAID} path of CICS tran {@code CM00} / program
     * {@code COMEN01C}.
     *
     * <p>Reproduces COBOL {@code MAIN-PARA} lines 91-103. The pressed key is
     * resolved to a {@link PfKey} and dispatched: {@code ENTER} runs
     * {@code PROCESS-ENTER-KEY} (see {@link #processEnter(COMEN01Form)}); {@code PF3}
     * ({@link PfKey#PFK03}) mirrors {@code XCTL COSGN00C} (see
     * {@link #returnToSignon()}); any other key is the COBOL {@code WHEN OTHER}
     * branch and re-displays the menu with {@link Messages#CCDA_MSG_INVALID_KEY}.</p>
     *
     * @param form  the bound menu form (COBOL {@code COMEN1AI}); supplies the entered
     *              option, exposed under the model attribute {@code form}
     * @param pfkey the PF-key token submitted by the template ({@code "ENTER"} or
     *              {@code "PF3"}); absent submissions default to {@code ENTER}
     * @return a {@code redirect:} to the selected screen (or sign-on), or the
     *         logical view name {@link #VIEW_MENU} when the menu is re-displayed
     */
    @PostMapping("/menu")
    public String handleMenu(@Valid @ModelAttribute("form") COMEN01Form form,
            BindingResult bindingResult,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = ENTER_TOKEN) String pfkey) {
        // Review finding #11: an over-width field (only reachable by a crafted client bypassing the
        // template maxlength / 3270 field width) re-displays the menu with a neutral banner and does
        // no option routing, so the COBOL menu edit ordering is untouched.
        if (bindingResult.hasErrors()) {
            renderMenu(form, MSG_FIELD_LENGTH);
            return VIEW_MENU;
        }
        PfKey key = resolvePfKey(pfkey);
        return switch (key) {
            case ENTER -> processEnter(form);
            case PFK03 -> returnToSignon();
            default -> {
                // COBOL MAIN-PARA WHEN OTHER (lines 99-102): CCDA-MSG-INVALID-KEY.
                renderMenu(form, Messages.CCDA_MSG_INVALID_KEY);
                yield VIEW_MENU;
            }
        };
    }

    /**
     * Processes the ENTER key - delegates to {@code PROCESS-ENTER-KEY} and maps its
     * outcome to a redirect or a menu re-display.
     *
     * <p>{@link MainMenuService#processEnterKey(COMEN01Form, CardDemoContext)}
     * parses and validates the option, applies the admin gate, and on a valid,
     * implemented option records the target program on the context
     * ({@link CardDemoContext#getToProgram()}) and resets the program context to
     * enter, returning {@link RoutingAction#REDIRECT} (the COBOL {@code XCTL}). This
     * method then looks the target program up in {@link #PROGRAM_ROUTES}, records
     * the target transaction id, and issues the {@code redirect:}. When the service
     * returns {@link RoutingAction#SHOW_MENU} (an invalid-option or admin-only error,
     * or the "coming soon" informational note) the menu is re-displayed with that
     * message.</p>
     *
     * @param form the bound menu form supplying the option and receiving the
     *             normalized echo / message
     * @return a {@code redirect:} to the selected screen, or {@link #VIEW_MENU} when
     *         the menu must be re-displayed
     */
    private String processEnter(COMEN01Form form) {
        MainMenuResult result = mainMenuService.processEnterKey(form, context);
        if (result.action() == RoutingAction.REDIRECT) {
            MenuTarget target = PROGRAM_ROUTES.get(context.getToProgram());
            if (target != null) {
                context.setToTranid(target.transactionId());
                return REDIRECT_PREFIX + target.route();
            }
            // Defensive: unreachable for the ten seeded options (all mapped above);
            // re-display the menu rather than emit an unmapped redirect.
            renderMenu(form, null);
            return VIEW_MENU;
        }
        // COBOL SEND-MENU-SCREEN with a message: invalid option / admin-only (error)
        // or the green "coming soon" note (informational). Text preserved verbatim.
        renderMenu(form, result.message());
        return VIEW_MENU;
    }

    /**
     * Returns control to the sign-on screen - the COBOL {@code PF3} branch
     * (lines 96-98) plus {@code RETURN-TO-SIGNON-SCREEN} (lines 170-177).
     *
     * <p>Records this screen as the origin of the hand-off
     * ({@code CDEMO-FROM-TRANID}/{@code CDEMO-FROM-PROGRAM}) and delegates to
     * {@link MainMenuService#returnToSignonScreen(CardDemoContext)} to set the
     * sign-on target ({@code CDEMO-TO-PROGRAM}/{@code CDEMO-TO-TRANID}) before
     * issuing the redirect.</p>
     *
     * @return a {@code redirect:} to the sign-on route
     */
    private String returnToSignon() {
        context.setFromTranid(TRANSACTION_ID);
        context.setFromProgram(PROGRAM_NAME);
        mainMenuService.returnToSignonScreen(context);
        return SIGNON_REDIRECT;
    }

    /**
     * Renders the menu screen - the controller half of {@code SEND-MENU-SCREEN}.
     *
     * <p>Populates the fixed header ({@code POPULATE-HEADER-INFO}) and the ten
     * option display lines ({@code BUILD-MENU-OPTIONS}) on the form, then sets the
     * message line ({@code MOVE WS-MESSAGE TO ERRMSGO}). A {@code null} message
     * clears the line, matching the COBOL {@code MOVE SPACES TO WS-MESSAGE} on the
     * no-message path.</p>
     *
     * @param form    the form to populate for rendering
     * @param message the message to show on the error/message line, or {@code null}
     *                for no message
     */
    private void renderMenu(COMEN01Form form, String message) {
        populateHeader(form);
        populateOptions(form);
        form.setErrmsg(message);
    }

    /**
     * Restricts request-parameter binding to the single field the {@code COMEN01} menu submits -
     * the option selector {@code option} (review finding #11). Header, title, date and message
     * fields are display-only and can no longer be over-posted; {@code pfkey} arrives as a
     * {@code @RequestParam} and is not bound through the form.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget() is null
        // when @InitBinder runs; the resolved binder.getTargetType() is the reliable discriminator.
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (COMEN01Form.class.equals(targetType)) {
            binder.setAllowedFields("option");
        }
    }

    /**
     * Populates the fixed screen header - migration of {@code POPULATE-HEADER-INFO}
     * (COBOL lines 212-231).
     *
     * <p>Sets the transaction id, program name, the two banner titles, and the
     * current date/time rendered with the COBOL {@code MM/DD/YY} and {@code HH:MM:SS}
     * edit masks via {@link DateStruct}.</p>
     *
     * @param form the form whose header fields are set
     */
    private void populateHeader(COMEN01Form form) {
        form.setTrnname(TRANSACTION_ID);
        form.setPgmname(PROGRAM_NAME);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the menu option display lines - migration of {@code BUILD-MENU-OPTIONS}
     * (COBOL lines 236-277).
     *
     * <p>{@link MainMenuService#buildMenuOptions()} returns the formatted
     * {@code "NN. Name"} lines in menu order (one per populated option); each is
     * placed into its {@code OPTN00n} display slot via {@link #assignOptionSlot}. The
     * standard catalog fills slots 1-10, leaving slots 11-12 blank, exactly as the
     * COBOL {@code EVALUATE WS-IDX} does for a ten-option count.</p>
     *
     * @param form the form whose option slots are set
     */
    private void populateOptions(COMEN01Form form) {
        List<String> lines = mainMenuService.buildMenuOptions();
        for (int index = 0; index < lines.size(); index++) {
            assignOptionSlot(form, index + 1, lines.get(index));
        }
    }

    /**
     * Places one formatted option line into its display slot - the COBOL
     * {@code EVALUATE WS-IDX WHEN 1..12} of {@code BUILD-MENU-OPTIONS}.
     *
     * <p>Slots outside 1-12 are ignored, mirroring the COBOL {@code WHEN OTHER ->
     * CONTINUE}; the standard ten-option catalog never reaches that branch.</p>
     *
     * @param form the form to update
     * @param slot the 1-based option slot number (COBOL {@code WS-IDX})
     * @param text the formatted {@code "NN. Name"} option line
     */
    private static void assignOptionSlot(COMEN01Form form, int slot, String text) {
        switch (slot) {
            case 1 -> form.setOptn001(text);
            case 2 -> form.setOptn002(text);
            case 3 -> form.setOptn003(text);
            case 4 -> form.setOptn004(text);
            case 5 -> form.setOptn005(text);
            case 6 -> form.setOptn006(text);
            case 7 -> form.setOptn007(text);
            case 8 -> form.setOptn008(text);
            case 9 -> form.setOptn009(text);
            case 10 -> form.setOptn010(text);
            case 11 -> form.setOptn011(text);
            case 12 -> form.setOptn012(text);
            default -> {
                // COBOL BUILD-MENU-OPTIONS EVALUATE WS-IDX WHEN OTHER -> CONTINUE.
            }
        }
    }

    /**
     * Resolves the web PF-key token to the shared {@link PfKey}, routing through
     * {@link PfKeyHandler} exactly as the COBOL {@code EVALUATE EIBAID} did.
     *
     * <p>The {@code COMEN01} Thymeleaf form submits the human-facing labels
     * {@code "ENTER"} and {@code "PF3"} in its {@code pfkey} parameter (mirroring the
     * 3270 keyboard), whereas {@link PfKeyHandler#fromAid(String)} recognizes CICS
     * AID mnemonics ({@code DFHENTER}, {@code DFHPFnn}). This helper bridges the two
     * by mapping the label to its AID mnemonic before delegating, so PF-key
     * resolution stays centralized in {@link PfKeyHandler}. A {@code null}, blank, or
     * unrecognized token yields {@link PfKey#OTHER}, matching the COBOL
     * {@code WHEN OTHER} branch; the lookup is upper-cased with {@link Locale#ROOT}
     * so it never varies with the default locale.</p>
     *
     * @param pfkey the raw PF-key token from the request (may be {@code null})
     * @return the resolved {@link PfKey}; {@link PfKey#OTHER} for {@code null},
     *         blank, or unrecognized input
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
     * Immutable web target for a selected menu option: the CICS transaction id to
     * record on the context and the Spring MVC route to redirect to.
     *
     * @param transactionId the target CICS transaction id (for example {@code CAVW})
     * @param route         the target web route (for example {@code /account/view})
     */
    private record MenuTarget(String transactionId, String route) {
    }
}
