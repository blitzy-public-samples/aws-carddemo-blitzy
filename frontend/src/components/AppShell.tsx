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
 * HTML), and every style value resolves to a theme token — including the named
 * `theme.layout.*` sizing tokens (drawer width, fill-parent width) hoisted into
 * the theme per QA finding M-25 — or an allowed keyword; no magic layout literal
 * is hardcoded at the call site.
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
import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react';

import { GetCurrentUser, IsAdmin, Logout } from '@/lib/auth';
import { ErrorAlert } from '@/components/ErrorAlert';
import type { CurrentUser } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/**
 * The side-navigation drawer width, the fill-parent width and the full-viewport
 * height are NO LONGER local constants (QA finding M-25): they are named theme
 * tokens (`theme.layout.drawerWidth`, `theme.layout.fullWidth`) defined once in
 * `src/app/theme.ts` and consumed symbolically through `sx={(theme) => …}` on the
 * nav container and both drawer papers so the three stay in lockstep and no magic
 * layout literal is hardcoded here.
 */

/** Header title shown in the AppBar. Source: `app/cpy/COTTL01Y.cpy`. */
const APP_TITLE = 'CardDemo';

/**
 * The pre-authentication signon route (`COSGN00`, CICS tx CC00). The shell is
 * intentionally NOT rendered here, so the login page appears without chrome.
 */
const SIGNON_ROUTE = '/signon';

/** Route the header brand returns to — the regular-user menu (`COMEN01`). */
const HOME_ROUTE = '/menu';

/** Label shown on the Logout button while a sign-out is in flight (QA Issue 2). */
const LOGGING_OUT_LABEL = 'Signing out…';

/** Default Logout button label. */
const LOGOUT_LABEL = 'Logout';

/**
 * Title of the alert shown when server-side sign-out could not be confirmed
 * (QA Issue 2 / CWE-613). The `Logout` helper rejects in this case; the user is
 * kept signed in and can press Logout again to retry.
 */
const LOGOUT_FAILED_TITLE = 'Sign-out failed';

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
/* Focus helpers (QA I28 — aria-hidden-aware focus placement).               */
/* ------------------------------------------------------------------------- */

/**
 * Upper bound on the number of animation frames {@link FocusWhenExposed} waits
 * for an `aria-hidden` ancestor to clear before focusing regardless. MUI's
 * temporary Drawer keeps `aria-hidden="true"` on the app root for the FULL
 * slide-out transition (~200 ms) and removes it only when the transition
 * completes; 30 frames (~0.5 s at 60 fps) comfortably covers that window while
 * guaranteeing the poll can never loop forever.
 */
const MAX_FOCUS_DEFER_FRAMES = 30;

/**
 * Returns `true` when `element` is itself, or has an ancestor, carrying
 * `aria-hidden="true"`. Focusing an element inside such a subtree makes Chromium
 * log "Blocked aria-hidden on an element because its descendant retained focus"
 * (QA I28), because a focused node must never be hidden from assistive technology.
 *
 * @param element - The candidate focus target.
 * @returns Whether the element currently sits inside an `aria-hidden` subtree.
 */
export function IsInsideAriaHidden(element: HTMLElement): boolean {
    let node: HTMLElement | null = element;
    while (node !== null) {
        if (node.getAttribute('aria-hidden') === 'true') {
            return true;
        }
        node = node.parentElement;
    }
    return false;
}

/**
 * Focuses `element`, but never while it sits inside an `aria-hidden` subtree.
 *
 * QA I28 — when the mobile navigation Drawer closes, MUI's Modal keeps
 * `aria-hidden="true"` on the app root for the entire slide-out transition and
 * removes it only once the transition completes. Placing focus on an app-root
 * descendant during that window — the hamburger toggle after an Escape/backdrop
 * dismissal, or the destination page `<h1>` after a nav-item click — trips
 * Chromium's blocked-aria-hidden warning. This helper defers the `focus()` call
 * one animation frame at a time until no `aria-hidden` ancestor remains (i.e.
 * until the app root is exposed to assistive technology again), then focuses.
 * When the target is already exposed — the common case: desktop navigation, or
 * any route change with no open Drawer — it focuses synchronously, preserving the
 * existing behavior. Bounded by {@link MAX_FOCUS_DEFER_FRAMES} so it can never
 * spin indefinitely.
 *
 * @param element - The element to focus once exposed; `null` is a no-op.
 */
export function FocusWhenExposed(element: HTMLElement | null): void {
    if (element === null || typeof window === 'undefined') {
        return;
    }
    const target: HTMLElement = element;
    let framesWaited = 0;
    function TryFocus(): void {
        if (
            IsInsideAriaHidden(target) &&
            framesWaited < MAX_FOCUS_DEFER_FRAMES
        ) {
            framesWaited += 1;
            window.requestAnimationFrame(TryFocus);
            return;
        }
        target.focus();
    }
    TryFocus();
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
    // QA Issue 2 (CWE-613): logout is now confirmed with the server before the
    // SPA shows a signed-out state. `isLoggingOut` disables the button while the
    // (bounded-retry) revocation is in flight; `logoutError` holds the failure
    // when revocation could not be confirmed so the user is kept signed in and
    // shown an explicit, retryable failure instead of a false success.
    const [isLoggingOut, setIsLoggingOut] = useState(false);
    const [logoutError, setLogoutError] = useState<unknown>(null);
    // QA I26 (RSC prefetch churn): next/link eagerly prefetches every in-viewport
    // <Link>. With the permanent desktop drawer that means ALL navigation
    // destinations prefetch their React Server Component payload on every page
    // load. Each nav link therefore starts with prefetch={false} — which, in the
    // App Router, disables BOTH viewport and hover auto-prefetch — and opts into
    // prefetching only once the user shows intent toward it (hover or keyboard
    // focus), limiting prefetch traffic to routes the user is actually likely to
    // visit. A path latches in this Set on first intent and stays prefetched.
    const [intendedNavPaths, setIntendedNavPaths] = useState<Set<string>>(
        () => new Set<string>(),
    );

    useEffect(() => {
        setCurrentUser(GetCurrentUser());
        setShowAdmin(IsAdmin());
        setIsMounted(true);
    }, [pathname]);

    // QA dest F-1 — client-side auth guard. On the mainframe an unauthenticated
    // terminal could only ever reach the signon map (COSGN00); every other map
    // required an established CICS session. Here a visitor with no client
    // identity who lands on a protected route (e.g. /accounts/view, /billpay —
    // screens that wait for id input and so never trigger the axios 401
    // interceptor on their own) is redirected to /signon. This is UX/navigation
    // only and NOT the security boundary: the backend still enforces auth on
    // every request (HTTP 401). `GetCurrentUser()` is read here (not the
    // `currentUser` state) so the check uses the freshest identity for this
    // pathname without waiting for a state commit. The /signon route is exempt so
    // the login page stays reachable.
    useEffect(() => {
        if (pathname !== SIGNON_ROUTE && GetCurrentUser() === null) {
            router.replace(SIGNON_ROUTE);
        }
    }, [pathname, router]);

    // The single `<main>` landmark, used to move focus to the new page's heading
    // on client-side navigation (QA N-02). One ref serves both render branches
    // (bare/pre-auth and the authenticated shell) because they are mutually
    // exclusive, so only one `<main>` is mounted at a time.
    const mainRef = useRef<HTMLElement | null>(null);

    // QA I28: reference to the hamburger toggle button so a keyboard/backdrop
    // dismissal of the temporary drawer can hand focus back to the control that
    // opened it, AFTER the close has removed `aria-hidden` from the app root.
    const menuButtonRef = useRef<HTMLButtonElement | null>(null);

    // Skips the very first render so the browser's natural initial focus (or a
    // page's `autoFocus` field) is never stolen on a full page load; the route
    // announcer only acts on subsequent client-side navigations.
    const isInitialRouteRef = useRef<boolean>(true);

    useEffect(() => {
        // QA N-02 — SPA route-change focus management. A client-side navigation
        // does not reload the document, so focus is left where it was (or falls
        // to <body>) and assistive-tech users are never told the page changed.
        // On each route change we move focus to the new page's main heading so
        // the new <h1> is announced. This ALSO lands focus in a valid place after
        // a dialog-driven navigation (e.g. /users/delete confirm -> redirect),
        // completing the retained-focus fix begun in ConfirmDialog.
        if (isInitialRouteRef.current) {
            isInitialRouteRef.current = false;
            return;
        }
        const main = mainRef.current;
        if (main === null) {
            return;
        }
        // Respect a page that legitimately claims focus for its primary control:
        // React applies `autoFocus` during commit (before this passive effect),
        // so if focus already rests on an element inside <main> (e.g. the account
        // /card/transaction id picker), leave it there rather than yanking focus
        // to the heading.
        const active = document.activeElement;
        if (
            active !== null &&
            active !== main &&
            main.contains(active)
        ) {
            return;
        }
        // Prefer the semantic page heading; fall back to the main landmark when a
        // page has not (yet) rendered an <h1>. `tabindex="-1"` makes the target
        // programmatically focusable WITHOUT adding it to the tab order, and
        // `:focus-visible` keeps the ring off for this scripted focus.
        const heading = main.querySelector<HTMLElement>('h1');
        const focusTarget: HTMLElement = heading ?? main;
        focusTarget.setAttribute('tabindex', '-1');
        // QA I28: when this navigation was triggered by tapping a mobile nav item,
        // the temporary Drawer is still sliding out and MUI keeps `aria-hidden` on
        // the app root (which contains this heading) for the whole transition.
        // Focusing the heading now would trip Chromium's blocked-aria-hidden
        // warning, so defer until the app root is exposed. With no open Drawer
        // (desktop nav, dialog-driven redirects) the target is already exposed and
        // `FocusWhenExposed` focuses synchronously, preserving prior behavior.
        FocusWhenExposed(focusTarget);
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
     * click and to the drawer backdrop / Escape `onClose`, so tapping a
     * destination both navigates (via `next/link`) and dismisses the overlay.
     *
     * QA I28 — avoid the browser's "Blocked aria-hidden on an element because its
     * descendant retained focus" warning. When MUI's Modal dismisses the drawer
     * it (a) applies `aria-hidden` to the rest of the application and (b) — by
     * default — RESTORES focus to the element that opened it (the hamburger
     * toggle) while that app-root subtree is still `aria-hidden`, which is
     * exactly what the browser blocks. A plain blur is insufficient because
     * MUI's own focus-restore re-focuses the hidden trigger after the blur.
     *
     * The fix has two coordinated parts:
     *   1. `disableRestoreFocus` on the drawer's Modal (see `ModalProps` below)
     *      stops MUI from moving focus back into the app root ON ITS OWN, which
     *      it would otherwise do the instant the close begins — while the app
     *      root is still `aria-hidden`.
     *   2. This handler blurs the active element immediately (so nothing inside
     *      the closing subtree keeps focus during the transition) and then, for a
     *      dismissal that does NOT navigate (Escape / backdrop tap), hands focus
     *      back to the hamburger toggle via {@link FocusWhenExposed}. Crucially,
     *      MUI keeps `aria-hidden` on the app root for the FULL ~200 ms slide-out
     *      transition, so the hand-off must wait until that attribute is gone —
     *      `FocusWhenExposed` polls animation frames until the toggle is no longer
     *      inside an `aria-hidden` subtree, then focuses it. Navigation
     *      dismissals skip this hand-off: the route-change effect lands focus on
     *      the destination page's <h1> (also via `FocusWhenExposed`).
     *
     * @param options - `returnFocusToToggle` requests the post-close focus
     *   hand-off back to the hamburger toggle (used for Escape / backdrop only).
     */
    function HandleDrawerClose(options?: { returnFocusToToggle?: boolean }): void {
        if (typeof document !== 'undefined') {
            const activeElement = document.activeElement;
            if (activeElement instanceof HTMLElement) {
                activeElement.blur();
            }
        }
        setMobileOpen(false);
        if (options?.returnFocusToToggle) {
            // Hand focus back to the control that opened the drawer, but only once
            // MUI has removed the app-root `aria-hidden` at the end of the slide-out
            // transition — focusing it earlier recreates the blocked-focus warning
            // this handler exists to prevent (QA I28).
            FocusWhenExposed(menuButtonRef.current);
        }
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
     * Logs the current user out. Delegates to `@/lib/auth` `Logout`, which asks
     * the backend to invalidate the HTTP-only session cookie (QA #17) and — only
     * once the server CONFIRMS revocation — clears the mirrored client identity
     * and hard-redirects to `/signon`.
     *
     * QA Issue 2 (CWE-613): `Logout` now REJECTS when server-side revocation
     * cannot be confirmed after its bounded retries, instead of silently
     * succeeding. This handler therefore awaits it (via the promise chain, since
     * the DOM `onClick` is synchronous), disables the button while in flight to
     * prevent overlapping attempts, and on rejection keeps the user signed in and
     * surfaces an explicit, retryable failure alert. On success the redirect
     * inside `Logout` navigates away before the `finally` matters.
     */
    function HandleLogout(): void {
        if (isLoggingOut) {
            return;
        }
        setLogoutError(null);
        setIsLoggingOut(true);
        void Logout()
            .catch((error: unknown) => {
                setLogoutError(error);
            })
            .finally(() => {
                setIsLoggingOut(false);
            });
    }

    /**
     * Dismisses the sign-out-failed alert. The user remains signed in; pressing
     * Logout again retries server revocation.
     */
    function HandleLogoutErrorClose(): void {
        setLogoutError(null);
    }

    /**
     * Keyboard handler mapping the legacy PF3 = Exit / Back key to the Escape key
     * (AAP §0.4.4). All other keys are ignored.
     *
     * @param event - The React keyboard event bubbling from a focused child.
     */
    function HandleKeyDown(event: ReactKeyboardEvent<HTMLDivElement>): void {
        // The AppShell is the SINGLE global fallback owner of the Escape = Back
        // shortcut (legacy PF3). A page (e.g. /accounts/update) or a modal
        // dialog that owns Escape for its own context stops propagation and/or
        // prevents default; honor that here so Escape is never handled twice
        // (QA M-28 double navigation).
        if (event.key === 'Escape' && !event.defaultPrevented) {
            router.back();
        }
    }

    /**
     * Latches a navigation destination as "intended" (the user hovered it or
     * moved keyboard focus to it) so its next render enables next/link
     * prefetching (QA I26). Idempotent: re-triggering for an already-latched
     * path returns the SAME Set reference, so React bails out of the state
     * update and no redundant render or prefetch scheduling occurs.
     *
     * @param path - The absolute route path the user has shown intent toward.
     */
    function MarkNavPathIntended(path: string): void {
        setIntendedNavPaths((previousPaths) => {
            if (previousPaths.has(path)) {
                return previousPaths;
            }
            const nextPaths = new Set(previousPaths);
            nextPaths.add(path);
            return nextPaths;
        });
    }

    /**
     * Renders one navigation entry as a `ListItem` → `ListItemButton` linked via
     * `next/link`. Extracted so the regular-options list and the admin-options
     * list (rendered as two separate `<ul>`s, see `RenderNavList`) share a single
     * definition (Ochs small-method / DRY rule) with no behavioral difference.
     *
     * @param item - The navigation entry to render.
     * @returns The list-item element for `item`.
     */
    function RenderNavItem(item: NavItem): ReactNode {
        // Highlight by route family so sibling sub-routes keep the same item
        // selected (QA #8): e.g. /accounts/update matches the "Accounts" item
        // linked to /accounts/view.
        const isActive =
            GetRouteFamily(pathname) === GetRouteFamily(item.path);
        return (
            <ListItem key={item.path} disablePadding>
                <ListItemButton
                    component={Link}
                    href={item.path}
                    // QA I26 (RSC prefetch churn): start with prefetch disabled
                    // — in the App Router prefetch={false} suppresses BOTH
                    // viewport and hover auto-prefetch — and switch to
                    // next/link's default (prefetch={null}) only once this
                    // destination is latched as "intended" via hover/keyboard
                    // focus below. This limits prefetch traffic to routes the
                    // user is actually likely to visit instead of eagerly
                    // fetching every nav destination's RSC payload on page load.
                    prefetch={intendedNavPaths.has(item.path) ? null : false}
                    onMouseEnter={() => MarkNavPathIntended(item.path)}
                    onFocus={() => MarkNavPathIntended(item.path)}
                    selected={isActive}
                    // FINDING-07 (WCAG 4.1.2): `selected` only supplies the
                    // Mui-selected visual highlight. `aria-current="page"`
                    // programmatically exposes the active destination to
                    // assistive technology so screen-reader users know which nav
                    // item is the current page; omitted (undefined) on inactive
                    // items.
                    aria-current={isActive ? 'page' : undefined}
                    onClick={() => HandleDrawerClose()}
                >
                    <ListItemIcon>{item.icon}</ListItemIcon>
                    <ListItemText primary={item.label} />
                </ListItemButton>
            </ListItem>
        );
    }

    /**
     * Renders the drawer's inner content: a spacer `Toolbar` that offsets the
     * fixed AppBar, followed by the scrollable navigation. The regular options
     * and the admin-only options are rendered as TWO separate `List` (`<ul>`)
     * elements with a `Divider` placed BETWEEN them, not inside either one.
     *
     * QA #13 accessibility fix: a `Divider` carries `role="separator"`, so
     * placing it inside a `<List>` (even as `component="li"`) gives that `<ul>` a
     * direct child whose role is not `listitem`, which fails the axe `list` rule.
     * Keeping the separator OUTSIDE every `<ul>` means each list contains only
     * `listitem` children. The layout is preserved byte-for-byte: the 8px
     * top/bottom padding that the single `List` used to contribute is moved onto
     * the wrapping `Box` (`py: 1` === `theme.spacing(1)` === 8px) and both lists
     * use `disablePadding`, so the divider still sits flush between the groups
     * exactly as before. Extracted as a small helper (Ochs small-method rule) and
     * reused by both the temporary and permanent drawers.
     *
     * @returns The drawer content element.
     */
    function RenderNavList(): ReactNode {
        const regularItems = visibleItems.filter((item) => {
            return !item.adminOnly;
        });
        const adminItems = visibleItems.filter((item) => {
            return item.adminOnly;
        });
        return (
            <>
                <Toolbar />
                <Box sx={{ overflow: 'auto', py: 1 }}>
                    {regularItems.length > 0 ? (
                        <List disablePadding>
                            {regularItems.map((item) => {
                                return RenderNavItem(item);
                            })}
                        </List>
                    ) : null}
                    {adminItems.length > 0 ? (
                        <>
                            {regularItems.length > 0 ? <Divider /> : null}
                            <List disablePadding>
                                {adminItems.map((item) => {
                                    return RenderNavItem(item);
                                })}
                            </List>
                        </>
                    ) : null}
                </Box>
            </>
        );
    }

    // Pre-auth bypass for the signon page: never wrap COSGN00 in the shell — the
    // login flow appears bare. QA #15: the children are still wrapped in a single
    // `<main>` landmark so every page (including /signon) exposes exactly one main
    // region for assistive tech. The wrapper is layout-neutral (a block element
    // filling its parent), so the centered signon form is unaffected.
    if (pathname === SIGNON_ROUTE) {
        return (
            <Box component="main" ref={mainRef}>
                {props.children}
            </Box>
        );
    }

    // QA Issue 30 + dest F-1 — gate the authenticated chrome on RESOLVED auth
    // state. The full shell (header, brand, nav labels) is rendered ONLY once the
    // mount effects have run AND a client identity is present. Until then —
    // during SSR and the very first client render (`isMounted` false), and for a
    // mounted anonymous visitor being redirected to /signon by the auth-guard
    // effect above (`currentUser` null) — an EMPTY `<main>` with NO header/nav is
    // rendered instead. This eliminates the brief flash of the static
    // authenticated shell frame an anonymous visitor previously saw (I30), and it
    // is hydration-exact: the server and the first client render both produce the
    // same empty main, so there is no mismatch; the switch to the full shell
    // happens only AFTER mount once `currentUser` is known. The authenticated
    // branch below renders its own single `<main>`, and these branches are
    // mutually exclusive, so there is never more than one main.
    if (!isMounted || !currentUser) {
        return <Box component="main" ref={mainRef} />;
    }

    return (
        <Box sx={{ display: 'flex' }} onKeyDown={HandleKeyDown}>
            <AppBar
                position="fixed"
                sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}
            >
                <Toolbar>
                    <IconButton
                        ref={menuButtonRef}
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
                                disabled={isLoggingOut}
                                aria-busy={isLoggingOut}
                            >
                                {isLoggingOut ? LOGGING_OUT_LABEL : LOGOUT_LABEL}
                            </Button>
                        </>
                    ) : null}
                </Toolbar>
            </AppBar>
            <Box
                component="nav"
                aria-label="Main navigation"
                sx={(theme) => ({
                    width: { md: theme.layout.drawerWidth },
                    flexShrink: { md: 0 },
                })}
            >
                <Drawer
                    variant="temporary"
                    open={mobileOpen}
                    onClose={() => HandleDrawerClose({ returnFocusToToggle: true })}
                    // QA I28: `disableRestoreFocus` stops MUI from restoring focus
                    // to the hamburger toggle while the app root is still
                    // `aria-hidden` during the close; `HandleDrawerClose` performs
                    // the focus hand-off itself on the next frame instead.
                    ModalProps={{ keepMounted: true, disableRestoreFocus: true }}
                    sx={(theme) => ({
                        display: { xs: 'block', md: 'none' },
                        '& .MuiDrawer-paper': {
                            width: theme.layout.drawerWidth,
                            boxSizing: 'border-box',
                        },
                    })}
                >
                    {RenderNavList()}
                </Drawer>
                <Drawer
                    variant="permanent"
                    open
                    sx={(theme) => ({
                        display: { xs: 'none', md: 'block' },
                        '& .MuiDrawer-paper': {
                            width: theme.layout.drawerWidth,
                            boxSizing: 'border-box',
                        },
                    })}
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
                ref={mainRef}
                sx={(theme) => ({
                    flexGrow: 1,
                    minWidth: 0,
                    maxWidth: theme.layout.fullWidth,
                    p: 3,
                })}
            >
                <Toolbar />
                {props.children}
            </Box>
            {/*
             * QA Issue 2 (CWE-613): when server-side sign-out cannot be confirmed,
             * `Logout` rejects and the user is kept signed in. Surface that
             * explicit, retryable failure here (severity="error"); dismissing it
             * leaves the session intact and pressing Logout again retries.
             */}
            <ErrorAlert
                open={Boolean(logoutError)}
                onClose={HandleLogoutErrorClose}
                error={logoutError}
                title={LOGOUT_FAILED_TITLE}
            />
        </Box>
    );
}
