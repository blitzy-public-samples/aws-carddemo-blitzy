'use client';

/**
 * page.tsx (route: /admin) -- Administrator Menu page.
 *
 * Traceability (Minimal Change Clause, AAP Section 0.8.1): modern Material UI
 * (Material Design 3) redesign of the legacy IBM 3270 admin menu.
 *   - BMS mapset / map  : COADM01 / COADM1A  (app/bms/COADM01.bms)
 *   - Symbolic map      : app/cpy-bms/COADM01.CPY
 *   - CICS transaction  : CA00
 *   - COBOL program     : COADM01C  (app/cbl/COADM01C.cbl, "Admin Menu for Admin users")
 *   - Admin option data : COADM02Y  (app/cpy/COADM02Y.cpy, CDEMO-ADMIN-OPT-COUNT = 4)
 *
 * The legacy COADM01C built a numbered option list (BUILD-MENU-OPTIONS) and, on
 * ENTER, validated a 1..4 selection then `XCTL`ed to the chosen COUSR00C-03C
 * program. Here that number-entry + PF-key paradigm is redesigned away (AAP
 * Section 0.3.3): the four security functions arrive from `GET /admin/menu` and
 * render as a clickable Material UI navigation list.
 *
 * SECURITY: this is an ADMIN-ONLY route (user_type === 'A'). The client guard
 * below is UX ONLY -- it hides the page and redirects a non-admin to /menu. The
 * server (require_admin -> HTTP 403) is the authoritative security boundary; the
 * 403 that `MenuApi.GetAdminMenu()` propagates is handled explicitly here because
 * the apiClient interceptor auto-redirects only on 401, never on 403.
 *
 * This page renders ONLY the menu content region. The application chrome
 * (AppBar / Drawer / CssBaseline / ThemeProvider) is supplied once by
 * src/app/layout.tsx; re-adding any of it here would double the shell.
 */

import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import NextLink from 'next/link';
import {
    Box,
    Card,
    CircularProgress,
    Container,
    List,
    ListItem,
    ListItemButton,
    ListItemIcon,
    ListItemText,
    Typography,
} from '@mui/material';
import {
    ChevronRight,
    ManageAccounts,
    People,
    PersonAdd,
    PersonRemove,
} from '@mui/icons-material';

import { MenuApi, IsApiError } from '@/lib/apiClient';
import { GetCurrentUser, IsAdmin } from '@/lib/auth';
import { ErrorAlert } from '@/components/ErrorAlert';
import type { CurrentUser, MenuOption, MenuResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores). These are    */
/* code identifiers / route paths / UI text -- not CSS values or secrets --   */
/* so they are permitted at module scope.                                     */
/* ------------------------------------------------------------------------- */

/** Admin role discriminator; mirrors the legacy CDEMO-USER-TYPE flag (COCOM01Y). */
const ADMIN_USER_TYPE = 'A';

/** Route a non-admin is sent to by the client guard -- the regular-user menu. */
const REDIRECT_ROUTE = '/menu';

/**
 * HTTP status the server's `require_admin` boundary returns for a non-admin
 * caller. Handled explicitly because the apiClient interceptor redirects only on
 * 401 -- a 403 is propagated to this page.
 */
const HTTP_FORBIDDEN = 403;

/**
 * Page-heading fallback. Mirrors the legacy `INITIAL='Admin Menu'` label in
 * `app/bms/COADM01.bms` (POS 4,35); used when the backend omits `menu_title`.
 */
const FALLBACK_MENU_TITLE = 'Admin Menu';

/** Sub-heading prompt, the modern stand-in for the legacy 'Please select an option :'. */
const SUBTITLE_TEXT = 'Select an administrative function';

/** Shown to a non-admin while the client guard redirects them to /menu. */
const ACCESS_DENIED_MESSAGE =
    'You do not have permission to access the administration menu.';

/** Empty-state text when the backend returns no admin functions. */
const NO_OPTIONS_MESSAGE = 'No administrative functions available.';

/**
 * Authoritative COBOL-program -> modern-route mapping, verified verbatim from
 * `app/cpy/COADM02Y.cpy` (CDEMO-ADMIN-OPT-COUNT = 4). These four entries map
 * 1:1 to the legacy `CDEMO-ADMIN-OPT-PGMNAME` -> `XCTL PROGRAM(...)` dispatch
 * targets in `COADM01C`. Any program the backend returns that is absent here is
 * rendered non-navigable (graceful degradation), never a crash.
 */
const PROGRAM_ROUTE_MAP: Readonly<Record<string, string>> = {
    COUSR00C: '/users',         // Option 1: User List (Security)
    COUSR01C: '/users/add',     // Option 2: User Add (Security)
    COUSR02C: '/users/update',  // Option 3: User Update (Security)
    COUSR03C: '/users/delete',  // Option 4: User Delete (Security)
};

/* ------------------------------------------------------------------------- */
/* Pure helpers (module scope; no component state -- small, single-purpose).  */
/* ------------------------------------------------------------------------- */

/**
 * Resolves a legacy COBOL program name to its modern frontend route.
 *
 * @param programName - The `program_name` from a returned {@link MenuOption}.
 * @returns The mapped route, or `null` when the program has no known route.
 */
function ResolveRoute(programName: string): string | null {
    return PROGRAM_ROUTE_MAP[programName] ?? null;
}

/**
 * Resolves a leading list icon for a known admin program, giving each security
 * function a meaningful glyph (list / add / update / delete). Unknown programs
 * get no icon (`null`), which renders cleanly as no leading icon.
 *
 * @param programName - The `program_name` from a returned {@link MenuOption}.
 * @returns The icon element, or `null` for an unmapped program.
 */
function ResolveIcon(programName: string): ReactNode {
    if (programName === 'COUSR00C') {
        return <People />;
    }
    if (programName === 'COUSR01C') {
        return <PersonAdd />;
    }
    if (programName === 'COUSR02C') {
        return <ManageAccounts />;
    }
    if (programName === 'COUSR03C') {
        return <PersonRemove />;
    }
    return null;
}

/**
 * Renders a single admin option as a Material UI list row. A mapped option is a
 * navigable `ListItemButton` (an accessible `<a>` via `next/link`) with a
 * trailing chevron; an unmapped option renders disabled so an unknown backend
 * function degrades gracefully instead of dead-linking.
 *
 * @param option - The menu option to render (single object -- Ochs <=4 params).
 * @returns The rendered list-item element.
 */
function RenderOptionItem(option: MenuOption): ReactNode {
    const targetRoute = ResolveRoute(option.program_name);
    const leadingIcon = ResolveIcon(option.program_name);
    const itemBody = (
        <>
            {leadingIcon ? <ListItemIcon>{leadingIcon}</ListItemIcon> : null}
            <ListItemText primary={option.option_name} />
        </>
    );
    if (targetRoute === null) {
        return (
            <ListItem key={option.option_number} disablePadding>
                <ListItemButton disabled>{itemBody}</ListItemButton>
            </ListItem>
        );
    }
    return (
        <ListItem key={option.option_number} disablePadding>
            <ListItemButton component={NextLink} href={targetRoute}>
                {itemBody}
                <ChevronRight />
            </ListItemButton>
        </ListItem>
    );
}

/* ------------------------------------------------------------------------- */
/* Page component.                                                           */
/* ------------------------------------------------------------------------- */

/**
 * Administrator Menu page (route `/admin`).
 *
 * On mount it (1) runs a client-side admin guard -- a non-admin is redirected to
 * `/menu` -- and (2), for an admin, fetches the admin menu from the backend and
 * renders it as a clickable Material UI list. A failed load surfaces through the
 * shared {@link ErrorAlert}; a server 403 additionally redirects to `/menu`.
 *
 * @returns The rendered admin menu content region.
 */
export default function AdminPage() {
    const router = useRouter();

    const [adminMenu, setAdminMenu] = useState<MenuResponse | null>(null);
    const [isLoading, setIsLoading] = useState<boolean>(true);
    const [errorState, setErrorState] = useState<unknown>(null);
    const [isAlertOpen, setIsAlertOpen] = useState<boolean>(false);
    const [isAuthorized, setIsAuthorized] = useState<boolean | null>(null);

    // Effect #1 -- admin client guard (runs once per router identity). The client
    // guard is UX only; the server enforces require_admin (HTTP 403). See the
    // LoadAdminMenu 403 handling below for the authoritative boundary.
    useEffect(() => {
        // GetCurrentUser()/IsAdmin() read localStorage and are client-only, so
        // they run here (inside the effect) -- never during render -- to keep the
        // server and first client render identical (no hydration mismatch).
        const currentUser: CurrentUser | null = GetCurrentUser();
        const isAdminUser =
            IsAdmin() || currentUser?.user_type === ADMIN_USER_TYPE;
        if (!isAdminUser) {
            setIsAuthorized(false);
            setIsLoading(false);
            router.replace(REDIRECT_ROUTE);
            return;
        }
        setIsAuthorized(true);
    }, [router]);

    // Loads the admin menu from the backend. The caught value is narrowed with
    // the typed IsApiError guard (never a bare catch-all): a 403 means the server
    // denied admin access, so we surface it AND redirect to /menu; any other
    // error just surfaces through the ErrorAlert.
    const LoadAdminMenu = useCallback(async () => {
        setIsLoading(true);
        try {
            const response: MenuResponse = await MenuApi.GetAdminMenu();
            setAdminMenu(response);
        } catch (caughtError) {
            if (IsApiError(caughtError) && caughtError.status === HTTP_FORBIDDEN) {
                setErrorState(caughtError);
                setIsAlertOpen(true);
                router.replace(REDIRECT_ROUTE);
                return;
            }
            setErrorState(caughtError);
            setIsAlertOpen(true);
        } finally {
            setIsLoading(false);
        }
    }, [router]);

    // Effect #2 -- fetch the menu only once the guard has resolved to authorized.
    useEffect(() => {
        if (isAuthorized === true) {
            void LoadAdminMenu();
        }
    }, [isAuthorized, LoadAdminMenu]);

    // Dismiss the error alert (its own auto-hide timeout also invokes this).
    const HandleAlertClose = useCallback(() => {
        setIsAlertOpen(false);
    }, []);

    const menuTitle = adminMenu?.menu_title ?? FALLBACK_MENU_TITLE;

    /**
     * Renders the loaded admin options inside an elevated `Card`. Options are
     * sorted by their legacy option number; an empty set shows
     * `NO_OPTIONS_MESSAGE` instead of an empty list.
     *
     * @returns The rendered options list (or the empty-state message).
     */
    const RenderOptionList = () => {
        const menuOptions = [...(adminMenu?.menu_options ?? [])].sort(
            (left, right) => left.option_number - right.option_number,
        );
        if (menuOptions.length === 0) {
            return (
                <Typography variant="body1" color="text.secondary">
                    {NO_OPTIONS_MESSAGE}
                </Typography>
            );
        }
        return (
            <Card>
                <List>
                    {menuOptions.map((option) => RenderOptionItem(option))}
                </List>
            </Card>
        );
    };

    /**
     * Chooses the body for the current state: an access-denied message for a
     * non-admin (mid-redirect), a centered spinner while the guard is pending or
     * the menu is loading, or the loaded option list for an authorized admin.
     *
     * @returns The state-appropriate body element.
     */
    const RenderBody = () => {
        if (isAuthorized === false) {
            return (
                <Typography variant="body1" color="text.secondary">
                    {ACCESS_DENIED_MESSAGE}
                </Typography>
            );
        }
        if (isAuthorized === null || isLoading) {
            return (
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
                    <CircularProgress />
                </Box>
            );
        }
        return (
            <>
                <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ mb: 2 }}
                >
                    {SUBTITLE_TEXT}
                </Typography>
                {RenderOptionList()}
            </>
        );
    };

    return (
        <Container maxWidth="md" sx={{ py: 3 }}>
            <Typography variant="h4" component="h1" gutterBottom>
                {menuTitle}
            </Typography>

            {RenderBody()}

            <ErrorAlert
                open={isAlertOpen}
                onClose={HandleAlertClose}
                error={errorState}
                severity="error"
            />
        </Container>
    );
}
