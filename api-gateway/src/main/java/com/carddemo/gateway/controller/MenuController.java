package com.carddemo.gateway.controller;

import com.carddemo.common.constant.MenuOptions;
import com.carddemo.common.constant.Messages;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;

import java.util.List;
import java.util.Map;

/**
 * REST menu-navigation controller re-platforming the CardDemo CICS menu programs
 * ``COMEN01C`` (main menu, transaction ``CM00``) and ``COADM01C`` (admin menu,
 * transaction ``CA00``).
 *
 * :purpose: Expose menu-listing and option-selection endpoints that reproduce the
 *  legacy pseudo-conversational flow: option normalization and validation, the
 *  main-menu admin-only role gate, program-to-route dispatch resolution, and the
 *  PF3 back-to-signon and invalid-key behaviors. Option data is read exclusively
 *  from :java:type:`com.carddemo.common.constant.MenuOptions`; the 3270 screen
 *  formatting of the legacy ``BUILD-MENU-OPTIONS`` and ``POPULATE-HEADER-INFO``
 *  paragraphs is intentionally not reproduced (the client renders labels and
 *  headers from the structured JSON returned here).
 */
@RestController
public class MenuController {

    /** :purpose: Main-menu CICS transaction id (COBOL ``COMEN01C`` ``WS-TRANID``). */
    private static final String MAIN_TRANID = "CM00";

    /** :purpose: Main-menu legacy program name (COBOL ``COMEN01C`` ``WS-PGMNAME``). */
    private static final String MAIN_PROGRAM = "COMEN01C";

    /** :purpose: Admin-menu CICS transaction id (COBOL ``COADM01C`` ``WS-TRANID``). */
    private static final String ADMIN_TRANID = "CA00";

    /** :purpose: Admin-menu legacy program name (COBOL ``COADM01C`` ``WS-PGMNAME``). */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** :purpose: Downstream route for the PF3 back target (legacy sign-on ``COSGN00C``). */
    private static final String SIGNON_ROUTE = "/auth";

    /** :purpose: Legacy sign-on program name set as the PF3 back target (``CDEMO-TO-PROGRAM``). */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** :purpose: Legacy sign-on transaction id paired with :java:field:`SIGNON_PROGRAM`. */
    private static final String SIGNON_TRANID = "CC00";

    /** :purpose: Dispatch guard prefix; the COBOL checks ``PGMNAME(1:5) NOT = 'DUMMY'``. */
    private static final String DUMMY_PREFIX = "DUMMY";

    /**
     * :purpose: ``HttpSession`` attribute key under which the pseudo-conversational
     *  :java:type:`SessionContext` is stored. This key must stay consistent with the
     *  key used by the other services' session handling (a coordination point; the
     *  ``com.carddemo.gateway.config.SessionConfig`` is not yet created).
     */
    private static final String SESSION_CONTEXT_ATTR = "sessionContext";

    /** :purpose: Action-key value modeling COBOL ``DFHENTER`` (submit / select). */
    private static final String AID_ENTER = "ENTER";

    /** :purpose: Action-key value modeling COBOL ``DFHPF3`` (exit back to sign-on). */
    private static final String AID_PF3 = "PF3";

    /**
     * :purpose: Invalid-option banner (COBOL ``COMEN01C`` L131 / ``COADM01C`` L131).
     *  Frozen literal: thirty-seven characters ending in three dots with no trailing
     *  space.
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * :purpose: Admin-only access-denied banner for the main menu (COBOL ``COMEN01C``
     *  L140-141). Frozen literal: ends with three dots followed by exactly one trailing
     *  space.
     */
    private static final String MSG_NO_ACCESS = "No access - Admin Only option... ";

    /**
     * :purpose: Leading segment of the coming-soon banner (COBOL ``STRING 'This option '``).
     *  Frozen literal: twelve characters including one trailing space.
     */
    private static final String COMING_SOON_PREFIX = "This option ";

    /**
     * :purpose: Trailing segment of the coming-soon banner (COBOL ``'is coming soon ...'``).
     *  Frozen literal: eighteen characters.
     */
    private static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * :purpose: Resolve a legacy target program name to its modern downstream service
     *  route on the gateway.
     * :output: Immutable map from each of the fourteen selectable legacy program names
     *  to the account, card, transaction, report, bill-pay, or user service route.
     */
    private static final Map<String, String> PROGRAM_ROUTES = Map.ofEntries(
            Map.entry("COACTVWC", "/accounts"),
            Map.entry("COACTUPC", "/accounts"),
            Map.entry("COCRDLIC", "/cards"),
            Map.entry("COCRDSLC", "/cards"),
            Map.entry("COCRDUPC", "/cards"),
            Map.entry("COTRN00C", "/transactions"),
            Map.entry("COTRN01C", "/transactions"),
            Map.entry("COTRN02C", "/transactions"),
            Map.entry("CORPT00C", "/reports"),
            Map.entry("COBIL00C", "/billpay"),
            Map.entry("COUSR00C", "/users"),
            Map.entry("COUSR01C", "/users"),
            Map.entry("COUSR02C", "/users"),
            Map.entry("COUSR03C", "/users"));

    /**
     * Return the role-filtered main menu (legacy ``COMEN01C``, transaction ``CM00``).
     *
     * :param session: the servlet HTTP session carrying the pseudo-conversational
     *  :java:type:`SessionContext`.
     * :returns: a :java:type:`MenuResponse` listing the visible main-menu options and
     *  their downstream target routes.
     */
    @GetMapping("/menu")
    public MenuResponse mainMenu(HttpSession session) {
        SessionContext ctx = getOrCreateSessionContext(session);
        ctx.setFromTranid(MAIN_TRANID);
        ctx.setFromProgram(MAIN_PROGRAM);
        ctx.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        session.setAttribute(SESSION_CONTEXT_ATTR, ctx);

        boolean regularUser = isRegularUser(ctx);
        List<MenuOptionView> options = MenuOptions.MAIN_MENU_OPTIONS.stream()
                .filter(option -> !(regularUser && "A".equals(option.userType())))
                .map(option -> new MenuOptionView(
                        option.optionNumber(),
                        option.optionName(),
                        option.programName(),
                        resolveRoute(option.programName())))
                .toList();
        return new MenuResponse(MAIN_TRANID, MAIN_PROGRAM, options, null);
    }

    /**
     * Return the admin menu (legacy ``COADM01C``, transaction ``CA00``).
     *
     * :param session: the servlet HTTP session carrying the pseudo-conversational
     *  :java:type:`SessionContext`.
     * :returns: a :java:type:`MenuResponse` listing all admin-menu options and their
     *  downstream target routes; no role filtering is applied.
     */
    @GetMapping("/admin/menu")
    public MenuResponse adminMenu(HttpSession session) {
        SessionContext ctx = getOrCreateSessionContext(session);
        ctx.setFromTranid(ADMIN_TRANID);
        ctx.setFromProgram(ADMIN_PROGRAM);
        ctx.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        session.setAttribute(SESSION_CONTEXT_ATTR, ctx);

        List<MenuOptionView> options = MenuOptions.ADMIN_MENU_OPTIONS.stream()
                .map(option -> new MenuOptionView(
                        option.optionNumber(),
                        option.optionName(),
                        option.programName(),
                        resolveRoute(option.programName())))
                .toList();
        return new MenuResponse(ADMIN_TRANID, ADMIN_PROGRAM, options, null);
    }

    /**
     * Select a main-menu option (legacy ``COMEN01C`` ``PROCESS-ENTER-KEY``, transaction
     * ``CM00``).
     *
     * :param request: the inbound selection carrying the entered option and action key.
     * :param session: the servlet HTTP session carrying the pseudo-conversational
     *  :java:type:`SessionContext`.
     * :returns: a :java:type:`MenuSelectionResponse` describing the dispatch target, the
     *  PF3 back navigation, or an informational coming-soon message.
     */
    @PostMapping("/menu/select")
    public MenuSelectionResponse selectMainMenu(@RequestBody MenuSelectionRequest request, HttpSession session) {
        return processSelection(
                request,
                session,
                MenuOptions.MAIN_MENU_OPTIONS,
                MenuOptions.CDEMO_MENU_OPT_COUNT,
                MAIN_TRANID,
                MAIN_PROGRAM,
                true,
                true);
    }

    /**
     * Select an admin-menu option (legacy ``COADM01C`` ``PROCESS-ENTER-KEY``, transaction
     * ``CA00``).
     *
     * :param request: the inbound selection carrying the entered option and action key.
     * :param session: the servlet HTTP session carrying the pseudo-conversational
     *  :java:type:`SessionContext`.
     * :returns: a :java:type:`MenuSelectionResponse` describing the dispatch target, the
     *  PF3 back navigation, or an informational coming-soon message.
     */
    @PostMapping("/admin/menu/select")
    public MenuSelectionResponse selectAdminMenu(@RequestBody MenuSelectionRequest request, HttpSession session) {
        return processSelection(
                request,
                session,
                MenuOptions.ADMIN_MENU_OPTIONS,
                MenuOptions.CDEMO_ADMIN_OPT_COUNT,
                ADMIN_TRANID,
                ADMIN_PROGRAM,
                false,
                false);
    }

    /**
     * Shared selection handler backing both ``/menu/select`` and ``/admin/menu/select``,
     * mirroring the legacy ``EVALUATE EIBAID`` plus ``PROCESS-ENTER-KEY`` flow.
     *
     * :param request: the inbound selection (may be ``null``); supplies the entered
     *  option and the action key.
     * :param session: the servlet HTTP session carrying the pseudo-conversational
     *  :java:type:`SessionContext`.
     * :param table: the option table to select from (main or admin).
     * :param count: the declared option count used to bound-check the entered option.
     * :param tranid: the originating transaction id recorded on the session context.
     * :param program: the originating program name recorded on the session context.
     * :param roleGate: whether to apply the main-menu admin-only role gate.
     * :param includeNameInComingSoon: whether the coming-soon message embeds the option
     *  name (main) or omits it (admin).
     * :returns: a :java:type:`MenuSelectionResponse` for dispatch, PF3 back navigation, or
     *  a coming-soon message.
     * :raises CardDemoException: for an unsupported action key, an invalid option, or a
     *  role-gated option.
     */
    private MenuSelectionResponse processSelection(
            MenuSelectionRequest request,
            HttpSession session,
            List<MenuOptions.MenuOption> table,
            int count,
            String tranid,
            String program,
            boolean roleGate,
            boolean includeNameInComingSoon) {

        SessionContext ctx = getOrCreateSessionContext(session);
        ctx.setFromTranid(tranid);
        ctx.setFromProgram(program);
        ctx.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);

        String aid = resolveAid(request == null ? null : request.aid());
        if (AID_PF3.equals(aid)) {
            ctx.setToProgram(SIGNON_PROGRAM);
            ctx.setToTranid(SIGNON_TRANID);
            session.setAttribute(SESSION_CONTEXT_ATTR, ctx);
            return new MenuSelectionResponse(true, null, SIGNON_ROUTE, null);
        }
        if (!AID_ENTER.equals(aid)) {
            session.setAttribute(SESSION_CONTEXT_ATTR, ctx);
            throw new CardDemoException(Messages.CCDA_MSG_INVALID_KEY);
        }

        session.setAttribute(SESSION_CONTEXT_ATTR, ctx);

        int optionNumber = normalizeOption(request == null ? null : request.option(), count);
        MenuOptions.MenuOption selected = table.get(optionNumber - 1);

        if (roleGate && isRegularUser(ctx) && "A".equals(selected.userType())) {
            throw new CardDemoException(MSG_NO_ACCESS);
        }

        if (!selected.programName().trim().startsWith(DUMMY_PREFIX)) {
            return new MenuSelectionResponse(
                    true,
                    selected.programName(),
                    resolveRoute(selected.programName()),
                    null);
        }

        String message = includeNameInComingSoon
                ? COMING_SOON_PREFIX + firstToken(selected.optionName()) + COMING_SOON_SUFFIX
                : COMING_SOON_PREFIX + COMING_SOON_SUFFIX;
        return new MenuSelectionResponse(false, selected.programName(), null, message);
    }

    /**
     * Normalize and validate an entered option, reproducing the COBOL ``OPTIONI``
     * ``PIC X(2) JUST RIGHT`` handling followed by the numeric and range checks.
     *
     * :param rawOption: the entered option text (may be ``null``); only the first two
     *  characters are significant.
     * :param count: the inclusive upper bound (declared option count) for a valid option.
     * :returns: the validated one-based option number.
     * :raises CardDemoException: with the invalid-option message when the value is
     *  non-numeric, zero, or greater than ``count``.
     */
    private int normalizeOption(String rawOption, int count) {
        String raw = (rawOption == null) ? "" : rawOption;
        if (raw.length() > 2) {
            raw = raw.substring(0, 2);
        }
        String field = (raw + "  ").substring(0, 2);
        int idx = 2;
        while (idx > 1 && field.charAt(idx - 1) == ' ') {
            idx--;
        }
        String sub = field.substring(0, idx);
        String justified = (sub.length() >= 2)
                ? sub.substring(sub.length() - 2)
                : " ".repeat(2 - sub.length()) + sub;
        String digits = justified.replace(' ', '0');
        if (!digits.matches("\\d{2}")) {
            throw new CardDemoException(MSG_INVALID_OPTION);
        }
        int value = Integer.parseInt(digits);
        if (value == 0 || value > count) {
            throw new CardDemoException(MSG_INVALID_OPTION);
        }
        return value;
    }

    /**
     * Report whether the session belongs to a regular (non-admin) user.
     *
     * :param ctx: the session context (may be ``null``).
     * :returns: ``true`` only when the context's user type is the regular-user type;
     *  a ``null`` context or ``null`` user type yields ``false``.
     */
    private boolean isRegularUser(SessionContext ctx) {
        return ctx != null && ctx.getUserType() == SessionContext.UserType.CDEMO_USRTYP_USER;
    }

    /**
     * Fetch the session-scoped :java:type:`SessionContext`, creating and storing a fresh
     * one when absent.
     *
     * :param session: the servlet HTTP session.
     * :returns: the existing or newly created session context.
     */
    private SessionContext getOrCreateSessionContext(HttpSession session) {
        Object attr = session.getAttribute(SESSION_CONTEXT_ATTR);
        if (attr instanceof SessionContext ctx) {
            return ctx;
        }
        SessionContext ctx = new SessionContext();
        session.setAttribute(SESSION_CONTEXT_ATTR, ctx);
        return ctx;
    }

    /**
     * Normalize the inbound action key, defaulting an absent or blank value to
     * ``ENTER`` and folding case for comparison.
     *
     * :param aid: the raw action key from the request (may be ``null`` or blank).
     * :returns: the upper-cased action key, or ``ENTER`` when the input is absent or blank.
     */
    private String resolveAid(String aid) {
        if (aid == null || aid.isBlank()) {
            return AID_ENTER;
        }
        return aid.trim().toUpperCase();
    }

    /**
     * Return the leading token of an option name, reproducing the COBOL
     * ``DELIMITED BY SPACE`` behavior (characters up to, but excluding, the first space).
     *
     * :param name: the option name (may be ``null``).
     * :returns: the substring before the first space, the whole value when it contains no
     *  space, or an empty string when ``name`` is ``null``.
     */
    private String firstToken(String name) {
        if (name == null) {
            return "";
        }
        int spaceIndex = name.indexOf(' ');
        return (spaceIndex < 0) ? name : name.substring(0, spaceIndex);
    }

    /**
     * Resolve a legacy program name to its downstream gateway route.
     *
     * :param programName: the legacy program name (may carry surrounding padding).
     * :returns: the mapped route, or ``null`` when the trimmed name is unmapped or the
     *  input is ``null``.
     */
    private String resolveRoute(String programName) {
        if (programName == null) {
            return null;
        }
        return PROGRAM_ROUTES.get(programName.trim());
    }

    /**
     * Inbound option-selection body, modeling the COBOL ``OPTIONI`` field and ``EIBAID``.
     *
     * :param option: the entered option text; preserved verbatim (including non-numeric or
     *  space input) so normalization matches the legacy behavior.
     * :param aid: the action key (``"ENTER"`` to select, ``"PF3"`` to exit); a ``null`` or
     *  blank value is treated as ``ENTER``.
     */
    public record MenuSelectionRequest(String option, String aid) {
    }

    /**
     * A single selectable menu option projected for the client.
     *
     * :param optionNumber: the one-based option number.
     * :param optionName: the display label (35-character space-padded legacy name).
     * :param programName: the legacy target program name.
     * :param targetRoute: the resolved downstream gateway route, or ``null`` when unmapped.
     */
    public record MenuOptionView(int optionNumber, String optionName, String programName, String targetRoute) {
    }

    /**
     * Response body for the ``GET /menu`` and ``GET /admin/menu`` listings.
     *
     * :param tranId: the originating transaction id (``CM00`` for main, ``CA00`` for admin).
     * :param programName: the originating legacy program name.
     * :param options: the visible menu options.
     * :param message: an optional informational message, or ``null`` when none applies.
     */
    public record MenuResponse(String tranId, String programName, List<MenuOptionView> options, String message) {
    }

    /**
     * Response body for the two ``/select`` endpoints.
     *
     * :param dispatched: ``true`` when the client should navigate to ``targetRoute`` (an
     *  option dispatch or a PF3 back); ``false`` for an informational coming-soon result.
     * :param programName: the resolved legacy target program name, or ``null`` for a PF3 back.
     * :param targetRoute: the downstream route to navigate to, or ``null`` for a coming-soon
     *  result.
     * :param message: the coming-soon message, or ``null`` when a dispatch occurred.
     */
    public record MenuSelectionResponse(boolean dispatched, String programName, String targetRoute, String message) {
    }
}
