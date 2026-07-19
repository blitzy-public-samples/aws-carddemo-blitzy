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
import com.aws.carddemo.dto.screen.COSGN00Form;
import com.aws.carddemo.service.online.SignonService;
import com.aws.carddemo.service.online.SignonService.SignonResult;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.Messages;
import com.aws.carddemo.util.constants.ScreenTitles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the AWS CardDemo sign-on screen.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COSGN00C.cbl} (CICS tran CC00, mapset
 * COSGN00). Signon screen; post-auth role-based redirect to COADM01C (CA00) /
 * COMEN01C (CM00).</p>
 *
 * <p>This class is the web-tier replacement for the CICS transaction {@code CC00}
 * whose program is {@code COSGN00C} (verified in {@code legacy/csd/CARDDEMO.CSD}:
 * {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)}). It is the foundational
 * entry-point controller: it renders the sign-on screen, drives authentication,
 * and on success mirrors the COBOL {@code XCTL} to the admin / user menu with a
 * Spring MVC {@code redirect:} keyed off the authenticated role (AAP &sect;0.3.3,
 * &sect;0.6.7).</p>
 *
 * <h2>Presentation and navigation only</h2>
 * <p>This controller performs presentation and navigation only. All sign-on field
 * validation and the routing decision live in {@link SignonService} (the migration
 * of the {@code COSGN00C} business paragraphs); the cleartext password comparison
 * that preserves functional parity lives in the Spring Security layer
 * ({@code CardDemoUserDetailsService} + {@code CardDemoAuthenticationProvider}),
 * not here. No repository access, no password comparison, and no business logic
 * are performed in this class. The COBOL paragraphs map as follows:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} (the {@code EVALUATE EIBAID} dispatch) &rarr;
 *       {@link #submitSignon(COSGN00Form, String, HttpServletRequest, HttpServletResponse)}
 *       plus {@link #resolvePfKey(String)}.</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link #processEnter(COSGN00Form, HttpServletRequest, HttpServletResponse)}
 *       (delegating blank-field validation to
 *       {@link SignonService#processEnterKey(COSGN00Form, CardDemoContext)}).</li>
 *   <li>{@code READ-USER-SEC-FILE} (success routing) &rarr; the
 *       {@link SignonService#readUserSecFile(String, CardDemoContext)} call plus the
 *       role-based {@code redirect:} in {@code processEnter}.</li>
 *   <li>{@code READ-USER-SEC-FILE} (failure branches) &rarr;
 *       {@link SignonService#mapAuthenticationFailure(org.springframework.security.core.AuthenticationException)}
 *       rendered on the screen.</li>
 *   <li>{@code SEND-SIGNON-SCREEN} / {@code POPULATE-HEADER-INFO} &rarr;
 *       {@link #renderSignon(COSGN00Form, String)} / {@link #populateHeader(COSGN00Form)}.</li>
 *   <li>{@code SEND-PLAIN-TEXT} (PF3 thank-you) &rarr; {@link #exit(COSGN00Form)}.</li>
 * </ul>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The CICS {@code COMMAREA} ({@code COCOM01Y}) is replaced by the session-scoped
 * {@link CardDemoContext}, which is injected and never referenced statically. Sign-on
 * is the application entry point, so the COBOL first entry ({@code EIBCALEN = 0},
 * i.e. {@link CardDemoContext#isNew()}) is the normal initial display of the empty
 * screen rather than a bounce; {@link #showSignon(COSGN00Form)} latches first entry
 * via {@link CardDemoContext#markInitialized()} after that display, mirroring the
 * {@code EXEC CICS RETURN TRANSID(CC00)} that establishes the COMMAREA. On a
 * successful sign-on the routing target's program context is reset to enter
 * ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}); the PF3 exit clears the context
 * ({@link CardDemoContext#clear()}), reproducing the COBOL {@code RETURN} that ends
 * the conversation.</p>
 *
 * <h2>Role-based routing (AAP &sect;0.6.7)</h2>
 * <p>In COBOL, {@code READ-USER-SEC-FILE} moves {@code SEC-USR-TYPE} to
 * {@code CDEMO-USER-TYPE} and {@code XCTL}s to {@code COADM01C} (admin) or
 * {@code COMEN01C} (user). Here {@link SignonService#readUserSecFile(String, CardDemoContext)}
 * writes the routing target into the context after authentication succeeds, and this
 * controller issues the matching redirect: an administrator
 * ({@link CardDemoContext#isAdmin()}, COBOL {@code SEC-USR-TYPE = 'A'}) is redirected
 * to {@code /admin/menu} (tran {@code CA00}, {@code AdminMenuController}); every other
 * user is redirected to {@code /menu} (tran {@code CM00}, {@code MenuController}).</p>
 *
 * <h2>PF-key semantics</h2>
 * <p>The COBOL {@code EVALUATE EIBAID} becomes explicit action dispatch. The
 * {@code COSGN00} template submits the pressed key in the {@code pfkey} request
 * parameter ({@code "ENTER"} or {@code "PF3"}); {@link #resolvePfKey(String)} bridges
 * that token to a CICS AID mnemonic and delegates to {@link PfKeyHandler#fromAid(String)}
 * so PF-key resolution stays centralized. Sign-on honors only {@code ENTER} (submit)
 * and {@code PF3} (exit); any other key yields the COBOL {@code WHEN OTHER}
 * invalid-key message.</p>
 *
 * <h2>Security integration by convention</h2>
 * <p>Authentication is delegated to Spring Security: this controller submits the
 * entered credentials to the injected {@link AuthenticationManager} (the
 * controller-managed sign-on shape), then, on success, saves the resulting
 * {@link SecurityContext} to the session via a {@link SecurityContextRepository} so
 * the authenticated identity survives the redirect. {@code config.SecurityConfig}
 * (authored separately) owns the filter chain and must {@code permitAll} the
 * {@code /} and {@code /signon} routes and authenticate against the custom user store
 * and provider; it is integrated by convention and is deliberately not imported here.
 * There are no hardcoded credentials anywhere in this class (AAP &sect;0.7.1).</p>
 *
 * <p>Constructor dependency injection, {@code private final} collaborators, no
 * Lombok, no field injection; a {@link Controller} (never {@code @RestController})
 * returning logical Thymeleaf view names, so no REST/JSON surface is introduced
 * (AAP &sect;0.3.4). Typed exceptions raised by the service tier are deliberately not
 * caught here (only the authentication failure is translated to a screen message);
 * they propagate to {@code exception.GlobalExceptionHandler}. Compiles warning-free
 * under {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see SignonService
 * @see CardDemoContext
 * @see COSGN00Form
 */
@Controller
public class SignonController {

    /** Logical Thymeleaf view name; resolves to {@code templates/COSGN00.html}. */
    private static final String VIEW_SIGNON = "COSGN00";

    /** Application root path; lands on the sign-on screen (CICS entry tran CC00). */
    private static final String PATH_ROOT = "/";

    /** Sign-on screen path (GET renders, POST submits). */
    private static final String PATH_SIGNON = "/signon";

    /** COBOL {@code WS-TRANID VALUE 'CC00'} - this screen's CICS transaction id. */
    private static final String TRANSACTION_ID = "CC00";

    /** COBOL {@code WS-PGMNAME VALUE 'COSGN00C'} - this screen's program name. */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** Model attribute name bound by the {@code COSGN00} template ({@code th:object}). */
    private static final String MODEL_ATTR_FORM = "form";

    /** Redirect to the sign-on route (used for the application root). */
    private static final String SIGNON_REDIRECT = "redirect:/signon";

    /** Admin redirect target - mirrors {@code XCTL COADM01C} (tran CA00). */
    private static final String ADMIN_MENU_REDIRECT = "redirect:/admin/menu";

    /** Regular-user redirect target - mirrors {@code XCTL COMEN01C} (tran CM00). */
    private static final String USER_MENU_REDIRECT = "redirect:/menu";

    /** Request-parameter name carrying the pressed PF-key from the COSGN00 form. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Web PF-key token for the ENTER action submitted by the COSGN00 template. */
    private static final String ENTER_TOKEN = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code PF3}). */
    private static final String PF_TOKEN_PREFIX = "PF";

    /** CICS AID mnemonic for ENTER, consumed by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix for program-function keys ({@code DFHPFnn}). */
    private static final String AID_PF_PREFIX = "DFHPF";

    /**
     * Sign-on business service (migration of the {@code COSGN00C} paragraphs).
     * Performs blank-field validation and, after authentication, records the
     * admin/user routing decision on the context; this controller only orchestrates
     * presentation, authentication, and navigation.
     */
    private final SignonService signonService;

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COCOM01Y}). Injected as a
     * scoped proxy and shared with {@link SignonService}, so both observe the same
     * per-session navigation and identity state.
     */
    private final CardDemoContext context;

    /**
     * Spring Security authentication entry point. Validates the entered credentials
     * against the custom user store / provider (preserving the COBOL cleartext
     * comparison behavior in the security layer). Provided by {@code SecurityConfig}
     * at runtime and integrated by convention.
     */
    private final AuthenticationManager authenticationManager;

    /**
     * Repository used to persist the {@link SecurityContext} to the HTTP session after
     * a controller-managed authentication, so the authenticated identity survives the
     * post-sign-on redirect. The Spring default ({@link HttpSessionSecurityContextRepository})
     * is used; it is a stateless collaborator and is not a Spring-injected bean.
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /**
     * Creates the sign-on controller with its injected collaborators.
     *
     * <p>The constructor only stores the references and invokes no overridable
     * method, so it is free of the {@code this-escape} lint category under the
     * zero-warning build. Because there is a single constructor, Spring performs
     * constructor injection without an explicit {@code @Autowired} annotation.</p>
     *
     * @param signonService         the sign-on business service; must not be {@code null}
     * @param context               the session-scoped {@link CardDemoContext}
     *                              (COMMAREA replacement); must not be {@code null}
     * @param authenticationManager the Spring Security {@link AuthenticationManager};
     *                              must not be {@code null}
     */
    public SignonController(SignonService signonService, CardDemoContext context,
            AuthenticationManager authenticationManager) {
        this.signonService = signonService;
        this.context = context;
        this.authenticationManager = authenticationManager;
    }

    /**
     * Redirects the application root to the sign-on screen (HTTP {@code GET /}).
     *
     * <p>CardDemo's entry transaction is {@code CC00} (sign-on), so the root path
     * lands the operator on the sign-on screen, matching the CICS behavior where a
     * fresh terminal starts at {@code COSGN00C}.</p>
     *
     * @return a {@code redirect:} to {@link #PATH_SIGNON}
     */
    @GetMapping(PATH_ROOT)
    public String root() {
        return SIGNON_REDIRECT;
    }

    /**
     * Displays the sign-on screen (HTTP {@code GET /signon}) - the COBOL first-entry
     * {@code SEND-SIGNON-SCREEN} path of CICS tran {@code CC00} / program {@code COSGN00C}.
     *
     * <p>Reproduces COBOL {@code MAIN-PARA} lines 80-83: on first entry
     * ({@code EIBCALEN = 0}, i.e. {@link CardDemoContext#isNew()}) the empty screen is
     * presented with its header populated. Because sign-on is the application entry
     * point this is the normal initial display, not a bounce; the context is latched
     * as initialized ({@link CardDemoContext#markInitialized()}) so the subsequent
     * submit is dispatched as re-entry (mirroring the {@code RETURN TRANSID} that
     * establishes the COMMAREA).</p>
     *
     * @param form the sign-on screen form, bound under the model attribute
     *             {@code form} so the {@code COSGN00} template can render it
     * @return the logical view name {@link #VIEW_SIGNON}
     */
    @GetMapping(PATH_SIGNON)
    public String showSignon(@ModelAttribute(MODEL_ATTR_FORM) COSGN00Form form) {
        // COBOL MAIN-PARA lines 80-83: first-entry initial display of the empty screen.
        populateHeader(form);
        if (context.isNew()) {
            // Latch the COMMAREA (EXEC CICS RETURN TRANSID(CC00)); next submit is re-entry.
            context.markInitialized();
        }
        return VIEW_SIGNON;
    }

    /**
     * Handles a sign-on submission (HTTP {@code POST /signon}) - the
     * {@code RECEIVE MAP} + {@code EVALUATE EIBAID} path of CICS tran {@code CC00} /
     * program {@code COSGN00C}.
     *
     * <p>Reproduces COBOL {@code MAIN-PARA} lines 84-95. The pressed key is resolved to
     * a {@link PfKey} and dispatched: {@code ENTER} ({@code DFHENTER}) runs
     * {@code PROCESS-ENTER-KEY} (see
     * {@link #processEnter(COSGN00Form, HttpServletRequest, HttpServletResponse)});
     * {@code PF3} ({@link PfKey#PFK03}, {@code DFHPF3}) shows the thank-you message and
     * ends the conversation (see {@link #exit(COSGN00Form)}); any other key is the COBOL
     * {@code WHEN OTHER} branch and re-displays the screen with the invalid-key message
     * (see {@link #invalidKey(COSGN00Form)}).</p>
     *
     * @param form     the bound sign-on form (COBOL {@code COSGN0AI}); supplies the
     *                 entered User ID and password under the model attribute {@code form}
     * @param pfkey    the PF-key token submitted by the template ({@code "ENTER"} or
     *                 {@code "PF3"}); an absent submission defaults to {@code ENTER},
     *                 matching the COBOL default of the ENTER key
     * @param request  the current request, used to persist the security context on success
     * @param response the current response, used to persist the security context on success
     * @return a {@code redirect:} to the role-based menu (or sign-on), or the logical view
     *         name {@link #VIEW_SIGNON} when the screen is re-displayed
     */
    @PostMapping(PATH_SIGNON)
    public String submitSignon(@ModelAttribute(MODEL_ATTR_FORM) COSGN00Form form,
            @RequestParam(name = PARAM_PFKEY, required = false, defaultValue = ENTER_TOKEN) String pfkey,
            HttpServletRequest request, HttpServletResponse response) {
        PfKey key = resolvePfKey(pfkey);
        return switch (key) {
            // COBOL WHEN DFHENTER -> PERFORM PROCESS-ENTER-KEY.
            case ENTER -> processEnter(form, request, response);
            // COBOL WHEN DFHPF3 -> MOVE CCDA-MSG-THANK-YOU, SEND-PLAIN-TEXT, RETURN.
            case PFK03 -> exit(form);
            // COBOL WHEN OTHER -> MOVE CCDA-MSG-INVALID-KEY, SEND-SIGNON-SCREEN.
            default -> invalidKey(form);
        };
    }

    /**
     * Processes the ENTER key - the Java migration of COBOL {@code PROCESS-ENTER-KEY}
     * plus the {@code READ-USER-SEC-FILE} hand-off.
     *
     * <p>First delegates blank-field validation to
     * {@link SignonService#processEnterKey(COSGN00Form, CardDemoContext)}: a blank User
     * ID or password yields a {@link SignonResult#isShowSignon()} error that is rendered
     * on the screen with the exact COBOL message ("Please enter User ID ..." /
     * "Please enter Password ..."). Otherwise the inputs are valid (COBOL
     * {@code IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE}) and the entered credentials
     * are submitted to the {@link AuthenticationManager}. On success the resulting
     * {@link SecurityContext} is saved to the session, the routing decision is written
     * into the context by {@link SignonService#readUserSecFile(String, CardDemoContext)}
     * (COBOL {@code MOVE SEC-USR-TYPE}/{@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}), and the
     * controller redirects by role (COBOL {@code XCTL COADM01C} / {@code COMEN01C}). An
     * {@link AuthenticationException} is translated to the matching COBOL screen message
     * ("User not found ..." / "Wrong Password ..." / "Unable to verify ...") by
     * {@link SignonService#mapAuthenticationFailure(org.springframework.security.core.AuthenticationException)}.</p>
     *
     * <p>The upper-cased User ID key ({@link CardDemoContext#getUserId()}, set by
     * {@code processEnterKey}) is used as the authentication principal and for the
     * post-authentication read, matching the COBOL {@code MOVE FUNCTION UPPER-CASE(USERIDI)}.</p>
     *
     * @param form     the bound sign-on form supplying the entered password and receiving
     *                 any error message
     * @param request  the current request, used to persist the security context on success
     * @param response the current response, used to persist the security context on success
     * @return a role-based {@code redirect:} on success, or {@link #VIEW_SIGNON} when the
     *         screen must be re-displayed with a validation or credential error
     */
    private String processEnter(COSGN00Form form, HttpServletRequest request,
            HttpServletResponse response) {
        // COBOL PROCESS-ENTER-KEY EVALUATE TRUE: blank User ID / password validation.
        SignonResult validation = signonService.processEnterKey(form, context);
        if (validation.isShowSignon()) {
            return renderSignon(form, validation.message());
        }

        // COBOL IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE: authenticate the credentials.
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            context.getUserId(), form.getPasswd()));
            establishSecurityContext(authentication, request, response);
            // COBOL READ-USER-SEC-FILE success (lines 222-240): routing target -> context.
            signonService.readUserSecFile(context.getUserId(), context);
            // COBOL IF CDEMO-USRTYP-ADMIN XCTL COADM01C ELSE XCTL COMEN01C.
            return context.isAdmin() ? ADMIN_MENU_REDIRECT : USER_MENU_REDIRECT;
        } catch (AuthenticationException failure) {
            // COBOL READ-USER-SEC-FILE failure branches (WHEN 13 / wrong password / WHEN OTHER).
            SignonResult mapped = signonService.mapAuthenticationFailure(failure);
            return renderSignon(form, mapped.message());
        }
    }

    /**
     * Ends the sign-on conversation with the thank-you message - the COBOL {@code DFHPF3}
     * branch ({@code MOVE CCDA-MSG-THANK-YOU}, {@code SEND-PLAIN-TEXT},
     * {@code EXEC CICS RETURN}).
     *
     * <p>Clears the session context ({@link CardDemoContext#clear()}) so the
     * pseudo-conversation is reset to a pristine first-entry state, reproducing the COBOL
     * {@code RETURN} that ends the conversation without re-establishing the COMMAREA, then
     * re-renders the sign-on screen carrying {@link Messages#CCDA_MSG_THANK_YOU}.</p>
     *
     * @param form the bound sign-on form to render with the thank-you message
     * @return the logical view name {@link #VIEW_SIGNON}
     */
    private String exit(COSGN00Form form) {
        context.clear();
        return renderSignon(form, Messages.CCDA_MSG_THANK_YOU);
    }

    /**
     * Re-displays the sign-on screen with the invalid-key message - the COBOL
     * {@code WHEN OTHER} branch ({@code MOVE 'Y' TO WS-ERR-FLG},
     * {@code MOVE CCDA-MSG-INVALID-KEY}, {@code SEND-SIGNON-SCREEN}).
     *
     * @param form the bound sign-on form to render with the invalid-key message
     * @return the logical view name {@link #VIEW_SIGNON}
     */
    private String invalidKey(COSGN00Form form) {
        return renderSignon(form, Messages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Renders the sign-on screen - the controller half of {@code SEND-SIGNON-SCREEN}.
     *
     * <p>Populates the fixed header ({@code POPULATE-HEADER-INFO}) and sets the message
     * line ({@code MOVE WS-MESSAGE TO ERRMSGO}). A {@code null} message clears the line,
     * matching the COBOL {@code MOVE SPACES TO WS-MESSAGE} on the no-message path.</p>
     *
     * @param form    the form to populate for rendering
     * @param message the message to show on the message line, or {@code null} for none
     * @return the logical view name {@link #VIEW_SIGNON}
     */
    private String renderSignon(COSGN00Form form, String message) {
        populateHeader(form);
        form.setErrmsg(message);
        return VIEW_SIGNON;
    }

    /**
     * Establishes and persists the authenticated security context after a
     * controller-managed sign-on.
     *
     * <p>Creates a fresh {@link SecurityContext}, sets the authenticated
     * {@link Authentication} on it, publishes it on the {@link SecurityContextHolder}
     * for the remainder of the request, and saves it through the
     * {@link SecurityContextRepository} so the identity is available on the
     * post-sign-on redirect and subsequent requests.</p>
     *
     * @param authentication the authenticated token returned by the
     *                       {@link AuthenticationManager}
     * @param request        the current request
     * @param response       the current response
     */
    private void establishSecurityContext(Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
    }

    /**
     * Populates the fixed screen header - migration of {@code POPULATE-HEADER-INFO}
     * (COBOL lines 177-204).
     *
     * <p>Sets the transaction id ({@code WS-TRANID}), program name ({@code WS-PGMNAME}),
     * the two banner titles ({@code CCDA-TITLE01}/{@code CCDA-TITLE02}), and the current
     * date/time rendered with the COBOL {@code MM/DD/YY} and {@code HH:MM:SS} edit masks
     * via {@link DateStruct}. The CICS-assigned {@code APPLID}/{@code SYSID} fields have
     * no web-tier equivalent and are intentionally left unset.</p>
     *
     * @param form the form whose header fields are set
     */
    private void populateHeader(COSGN00Form form) {
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
     * <p>The {@code COSGN00} template submits the human-facing labels {@code "ENTER"} and
     * {@code "PF3"} in its {@code pfkey} parameter (mirroring the 3270 keyboard), whereas
     * {@link PfKeyHandler#fromAid(String)} recognizes CICS AID mnemonics ({@code DFHENTER},
     * {@code DFHPFnn}). This helper bridges the two by mapping the label to its AID mnemonic
     * before delegating, so PF-key resolution stays centralized in {@link PfKeyHandler}. A
     * {@code null}, blank, or unrecognized token yields {@link PfKey#OTHER}, matching the
     * COBOL {@code WHEN OTHER} branch; the lookup is upper-cased with {@link Locale#ROOT} so
     * it never varies with the default locale.</p>
     *
     * @param pfkey the raw PF-key token from the request (may be {@code null})
     * @return the resolved {@link PfKey}; {@link PfKey#OTHER} for {@code null}, blank, or
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
}
