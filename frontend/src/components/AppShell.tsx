'use client';

/**
 * AppShell — the application shell / frame that wraps every AUTHENTICATED page.
 *
 * It renders a fixed top `AppBar` + `Toolbar` (header, identity and logout) and a
 * side `Drawer` + `List` navigation, redesigned per Material Design 3 (AAP §0.3,
 * §0.4.4). It is the modern replacement for the 3270 screen header and the
 * menu-driven navigation of the two CICS menu programs:
 *   - `COMEN01C` / map `app/cpy-bms/COMEN01.CPY` — the regular-user menu; its ten
 *     options live in `app/cpy/COMEN02Y.cpy` and collapse into the non-admin
 *     `NAV_ITEMS` entries (Menu, Accounts, Cards, Transactions, Reports, Bill Pay).
 *   - `COADM01C` / map `app/cpy-bms/COADM01.CPY` — the admin menu; its options live
 *     in `app/cpy/COADM02Y.cpy` (User List/Add/Update/Delete = `COUSR00C`–`COUSR03C`)
 *     and collapse into the two `adminOnly` entries (Admin, Users).
 * The header title text corresponds to `app/cpy/COTTL01Y.cpy` ('CardDemo').
 *
 * TRACEABILITY — identity / role (COMMAREA → stateless auth, AAP §0.5.5): on the
 * mainframe the signed-on user id and role (`CDEMO-USER-ID`, `CDEMO-USER-TYPE`
 * in `app/cpy/COCOM01Y.cpy`) were propagated program-to-program in the
 * `CARDDEMO-COMMAREA`. There is no COMMAREA here; the shell reads the same
 * identity / role from stateless client context via `@/lib/auth`.
 *
 * PF-key mapping (AAP §0.4.4): the legacy PF3 = Exit / Back is mapped to the
 * Escape key (`HandleKeyDown` → `router.back()`); PF-key actions in general map
 * to MUI `Button`s plus keyboard shortcuts.
 *
 * SECURITY (Ochs Test Rule): the role-based hiding of admin destinations below is
 * CLIENT-SIDE UX ONLY — it is NOT the authorization boundary. The backend
 * independently enforces admin access via its `require_admin` dependency (HTTP
 * 403), so a tampered client role cannot grant real access. The shell renders
 * only non-sensitive identity fields (`first_name`, `last_name`, `user_type`);
 * it never renders passwords or tokens.
 *
 * DESIGN SYSTEM (AAP §0.3.4): only Material UI components are emitted (never raw
 * HTML), and every style value resolves to a theme-scale `sx` token or an allowed
 * keyword; the sole permitted numeric layout constant is `DRAWER_WIDTH`.
 * `AppRouterCacheProvider` + `ThemeProvider` + `CssBaseline` are supplied by
 * `src/app/layout.tsx`; this component is rendered INSIDE that provider tree and
 * must not add them.
 *
 * @packageDocumentation
 */

import {
    AppBar,
    Toolbar,
    Drawer,
    List,
    ListItem,
    ListItemButton,
    ListItemIcon,
    ListItemText,
    Box,
    Typography,
    Divider,
    IconButton,
    Button,
} from '@mui/material';
import {
    Dashboard,
    AccountBalance,
    CreditCard,
    ReceiptLong,
    Assessment,
    Payment,
    AdminPanelSettings,
    People,
    Logout as LogoutIcon,
    Menu as MenuIcon,
} from '@mui/icons-material';
import { usePathname, useRouter } from 'next/navigation';
import Link from 'next/link';
import { Fragment, useEffect, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react';

import { GetCurrentUser, IsAdmin, Logout } from '@/lib/auth';
import type { CurrentUser } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/**
 * Width of the side navigation drawer, in pixels. This is the ONE permitted
 * numeric layout dimension for the shell (AAP §0.3.4 design-system rules); it is
 * applied symbolically through `sx` on both the nav container and the drawer
 * paper so the two always stay in lockstep.
 */
const DRAWER_WIDTH = 240;

/** Header title shown in the AppBar. Source: `app/cpy/COTTL01Y.cpy`. */
const APP_TITLE = 'CardDemo';

/**
 * The pre-authentication signon route (`COSGN00`, CICS tx CC00). The shell is
 * intentionally NOT rendered here, so the login page appears without chrome.
 */
const SIGNON_ROUTE = '/signon';

/** Route the header brand returns to — the regular-user menu (`COMEN01`). */
const HOME_ROUTE = '/menu';

/* ------------------------------------------------------------------------- */
/* Navigation model (single typed source of truth).                          */
/* ------------------------------------------------------------------------- */

/** One entry in the side-navigation list. */
interface NavItem {
    /** Human-readable label rendered by `ListItemText`. */
    label: string;
    /** Destination route linked on selection. */
    path: string;
    /** Leading icon element rendered by `ListItemIcon`. */
    icon: ReactNode;
    /** When `true`, shown only to admin users (`user_type === 'A'`). */
    adminOnly: boolean;
}

/**
 * The complete navigation inventory. The six non-admin entries mirror the regular
 * menu options of `app/cpy/COMEN02Y.cpy`; the two `adminOnly` entries mirror the
 * admin menu (`app/cpy/COADM02Y.cpy` → `COUSR00C`–`COUSR03C`). No entry is created
 * for `/signon` (pre-auth).
 */
const NAV_ITEMS: NavItem[] = [
    { label: 'Menu', path: '/menu', icon: <Dashboard />, adminOnly: false },
    { label: 'Accounts', path: '/accounts/view', icon: <AccountBalance />, adminOnly: false },
    { label: 'Cards', path: '/cards', icon: <CreditCard />, adminOnly: false },
    { label: 'Transactions', path: '/transactions', icon: <ReceiptLong />, adminOnly: false },
    { label: 'Reports', path: '/reports', icon: <Assessment />, adminOnly: false },
    { label: 'Bill Pay', path: '/billpay', icon: <Payment />, adminOnly: false },
    { label: 'Admin', path: '/admin', icon: <AdminPanelSettings />, adminOnly: true },
    { label: 'Users', path: '/users', icon: <People />, adminOnly: true },
];

/**
 * Returns the top-level "route family" of a path — its first path segment with a
 * leading slash (e.g. `/accounts/view` and `/accounts/update` both yield
 * `/accounts`; `/cards` yields `/cards`). The side-nav highlight is computed by
 * comparing families rather than exact paths so that a destination's sibling
 * sub-routes (view / update / add / delete) keep the same nav item selected
 * (QA issue #8: `/accounts/update` previously highlighted nothing because the
 * "Accounts" item links to `/accounts/view`). Every `NAV_ITEMS` entry has a
 * DISTINCT first segment, so families never collide.
 *
 * @param path - An absolute route path (e.g. from `usePathname()`).
 * @returns The `/`-prefixed first segment, or `/` for the root.
 */
function GetRouteFamily(path: string): string {
    const segments = path.split('/').filter(Boolean);
    if (segments.length === 0) {
        return '/';
    }
    return `/${segments[0]}`;
}

/* ------------------------------------------------------------------------- */
/* Props (single typed object — Ochs ≤4-parameter rule).                     */
/* ------------------------------------------------------------------------- */

/** Props for {@link AppShell}. */
export interface AppShellProps {
    /** The authenticated page content rendered inside the shell's main region. */
    children: ReactNode;
}

/* ------------------------------------------------------------------------- */
/* Component.                                                                */
/* ------------------------------------------------------------------------- */

/**
 * Renders the persistent application chrome (header + role-based side nav) around
 * the supplied page `children`. On the `/signon` route, and once mounted for an
 * unauthenticated visitor, it renders the children WITHOUT any chrome.
 *
 * @param props - The {@link AppShellProps} carrying the page content.
 * @returns The shell-wrapped (or, pre-auth, bare) page content.
 *
 * @example
 * ```tsx
 * // In src/app/layout.tsx, inside <ThemeProvider>:
 * <AppShell>{children}</AppShell>
 * ```
 */
export function AppShell(props: AppShellProps) {
    const pathname = usePathname();
    const router = useRouter();

    // Client-only identity. `GetCurrentUser()` / `IsAdmin()` read localStorage,
    // which does not exist during SSR, so they are read inside an effect (never
    // during render) to prevent a server / client hydration mismatch. Re-reading
    // on `pathname` change keeps identity and role fresh across login / logout.
    const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
    const [showAdmin, setShowAdmin] = useState(false);
    const [isMounted, setIsMounted] = useState(false);
    const [mobileOpen, setMobileOpen] = useState(false);

    useEffect(() => {
        setCurrentUser(GetCurrentUser());
        setShowAdmin(IsAdmin());
        setIsMounted(true);
    }, [pathname]);

    // Role-gated nav (client-side UX only — NOT the security boundary; see the
    // file header). Admin-only destinations are dropped for regular users.
    const visibleItems = NAV_ITEMS.filter((item) => {
        return !item.adminOnly || showAdmin;
    });

    // Defensive, non-sensitive identity label. `first_name` / `last_name` are
    // optional on CurrentUser; fall back to the user id, and never surface a
    // secret. React renders `undefined` as nothing, but an explicit label avoids
    // stray whitespace / "undefined" text (defensive UI rule).
    let identityLabel = '';
    if (currentUser) {
        const fullName = [currentUser.first_name, currentUser.last_name]
            .filter(Boolean)
            .join(' ');
        identityLabel = `${fullName || currentUser.user_id} (${currentUser.user_type})`;
    }

    /**
     * Toggles the temporary (mobile) navigation drawer. Bound to the AppBar
     * hamburger `IconButton`, which is shown only below the `md` breakpoint.
     */
    function HandleDrawerToggle(): void {
        setMobileOpen((previousOpen) => {
            return !previousOpen;
        });
    }

    /**
     * Closes the temporary (mobile) navigation drawer. Bound to each nav item's
     * click and to the drawer backdrop `onClose`, so tapping a destination both
     * navigates (via `next/link`) and dismisses the overlay.
     */
    function HandleDrawerClose(): void {
        setMobileOpen(false);
    }

    /**
     * Programmatic navigation helper. Used by the header brand to return to the
     * regular-user menu (`HOME_ROUTE`); the drawer items navigate declaratively
     * via `next/link` instead.
     *
     * @param path - The destination route to push onto the history stack.
     */
    function HandleNavigate(path: string): void {
        router.push(path);
    }

    /**
     * Logs the current user out. Delegates to `@/lib/auth` `Logout`, which now
     * asks the backend to invalidate the HTTP-only session cookie (QA #17) before
     * clearing the mirrored client identity and hard-redirecting to `/signon`.
     * `Logout` is async; its promise is intentionally not awaited here (the DOM
     * `onClick` handler is synchronous) — teardown and the redirect run inside
     * `Logout` itself, so `void` marks the fire-and-forget call explicitly.
     */
    function HandleLogout(): void {
        void Logout();
    }

    /**
     * Keyboard handler mapping the legacy PF3 = Exit / Back key to the Escape key
     * (AAP §0.4.4). All other keys are ignored.
     *
     * @param event - The React keyboard event bubbling from a focused child.
     */
    function HandleKeyDown(event: ReactKeyboardEvent<HTMLDivElement>): void {
        if (event.key === 'Escape') {
            router.back();
        }
    }

    /**
     * Renders the drawer's inner content: a spacer `Toolbar` that offsets the
     * fixed AppBar, followed by the scrollable navigation `List`. A `Divider` is
     * inserted before the first admin-only entry to separate it from the regular
     * options. Extracted as a small helper (Ochs small-method rule) and reused by
     * both the temporary and permanent drawers.
     *
     * @returns The drawer content element.
     */
    function RenderNavList(): ReactNode {
        return (
            <>
                <Toolbar />
                <Box sx={{ overflow: 'auto' }}>
                    <List>
                        {visibleItems.map((item, index) => {
                            // Highlight by route family so sibling sub-routes keep
                            // the same item selected (QA #8): e.g. /accounts/update
                            // matches the "Accounts" item linked to /accounts/view.
                            const isActive =
                                GetRouteFamily(pathname) ===
                                GetRouteFamily(item.path);
                            const needsDivider =
                                item.adminOnly &&
                                (index === 0 || !visibleItems[index - 1].adminOnly);
                            return (
                                <Fragment key={item.path}>
                                    {/*
                                     * QA #13: a bare <Divider> renders an <hr>,
                                     * which is invalid as a direct child of the
                                     * <List>'s <ul>. `component="li"` makes it a
                                     * valid list item (role="separator" preserved).
                                     */}
                                    {needsDivider ? <Divider component="li" /> : null}
                                    <ListItem disablePadding>
                                        <ListItemButton
                                            component={Link}
                                            href={item.path}
                                            selected={isActive}
                                            onClick={HandleDrawerClose}
                                        >
                                            <ListItemIcon>
                                                {item.icon}
                                            </ListItemIcon>
                                            <ListItemText primary={item.label} />
                                        </ListItemButton>
                                    </ListItem>
                                </Fragment>
                            );
                        })}
                    </List>
                </Box>
            </>
        );
    }

    // Pre-auth bypass: never wrap the signon page, and (once mounted) never wrap a
    // page for a visitor with no client identity — render the children WITHOUT the
    // shell chrome so the login flow and any redirect happen bare. This stays
    // hydration-safe: during SSR and the first client render `isMounted` is false,
    // so the branch matches on both sides; the identity-based switch only happens
    // AFTER mount via the effect above.
    //
    // QA #15: the children are still wrapped in a `<main>` landmark so every page
    // (including /signon) exposes exactly one main region for assistive tech. The
    // wrapper is layout-neutral (a block element filling its parent), so the
    // centered signon form is unaffected. The authenticated branch below renders
    // its own single `<main>`, and these two branches are mutually exclusive, so
    // there is never more than one main landmark.
    if (pathname === SIGNON_ROUTE || (isMounted && !currentUser)) {
        return <Box component="main">{props.children}</Box>;
    }

    return (
        <Box sx={{ display: 'flex' }} onKeyDown={HandleKeyDown}>
            <AppBar
                position="fixed"
                sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}
            >
                <Toolbar>
                    <IconButton
                        color="inherit"
                        aria-label="Open navigation menu"
                        edge="start"
                        onClick={HandleDrawerToggle}
                        sx={{ mr: 2, display: { md: 'none' } }}
                    >
                        <MenuIcon />
                    </IconButton>
                    <Button
                        color="inherit"
                        onClick={() => HandleNavigate(HOME_ROUTE)}
                        sx={{ textTransform: 'none' }}
                    >
                        <Typography variant="h6" component="span">
                            {APP_TITLE}
                        </Typography>
                    </Button>
                    <Box sx={{ flexGrow: 1 }} />
                    {currentUser ? (
                        <>
                            {/*
                             * QA #7: on narrow viewports the identity label
                             * crowded/overlapped the Logout button. `noWrap`
                             * keeps it to a single line (ellipsizing if long),
                             * and it is hidden below `sm` so the brand + Logout
                             * never collide on small screens (the role is still
                             * conveyed by the menu the user sees).
                             */}
                            <Typography
                                variant="body2"
                                noWrap
                                sx={{ mr: 2, display: { xs: 'none', sm: 'block' } }}
                            >
                                {identityLabel}
                            </Typography>
                            <Button
                                color="inherit"
                                startIcon={<LogoutIcon />}
                                onClick={HandleLogout}
                            >
                                Logout
                            </Button>
                        </>
                    ) : null}
                </Toolbar>
            </AppBar>
            <Box
                component="nav"
                aria-label="Main navigation"
                sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}
            >
                <Drawer
                    variant="temporary"
                    open={mobileOpen}
                    onClose={HandleDrawerClose}
                    ModalProps={{ keepMounted: true }}
                    sx={{
                        display: { xs: 'block', md: 'none' },
                        '& .MuiDrawer-paper': {
                            width: DRAWER_WIDTH,
                            boxSizing: 'border-box',
                        },
                    }}
                >
                    {RenderNavList()}
                </Drawer>
                <Drawer
                    variant="permanent"
                    open
                    sx={{
                        display: { xs: 'none', md: 'block' },
                        '& .MuiDrawer-paper': {
                            width: DRAWER_WIDTH,
                            boxSizing: 'border-box',
                        },
                    }}
                >
                    {RenderNavList()}
                </Drawer>
            </Box>
            {/*
             * `minWidth: 0` lets this flex item shrink below its content's
             * intrinsic width (flex items default to `min-width: auto`). Without
             * it, wide children such as the shared DataTable force the whole page
             * to overflow horizontally on narrow viewports; with it, those
             * children scroll internally instead (QA #2 systemic fix). `maxWidth`
             * clamps the column to the available flex space.
             */}
            <Box
                component="main"
                sx={{ flexGrow: 1, minWidth: 0, maxWidth: '100%', p: 3 }}
            >
                <Toolbar />
                {props.children}
            </Box>
        </Box>
    );
}
