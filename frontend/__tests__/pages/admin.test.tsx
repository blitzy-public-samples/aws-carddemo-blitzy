/** Admin Menu page spec — legacy origin BMS COADM01 / Tx CA00 / program COADM01C. Admin-only. */

/*
 * Component / integration spec for the modern Administrator Menu page
 * (`@/app/admin/page`, default export `AdminPage`) — the greenfield replacement
 * for the legacy 3270 admin menu COADM01 (CICS transaction CA00, COBOL program
 * COADM01C). Legacy layout source of truth: app/bms/COADM01.bms +
 * app/cpy-bms/COADM01.CPY, with the four-entry admin option table COADM02Y
 * (CDEMO-ADMIN-OPT-COUNT = 4).
 *
 * The REAL page is rendered inside the application Material UI theme (via
 * `RenderWithProviders`); its four collaborators are mocked so that no real
 * network request and no real router navigation are ever exercised:
 *
 *   - `next/navigation` — the App Router hooks. `useRouter` returns a STABLE
 *     `mockRouter` object so the page's `useCallback`/`useEffect` dependencies
 *     (which include `router`) stay referentially stable across renders;
 *     otherwise `LoadAdminMenu` would refetch on every render and break the
 *     call-count assertions. This is the modern redesign of the legacy
 *     `XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME)` dispatch in COADM01C.
 *
 *   - `@/lib/auth` — automocked; `IsAdmin` / `GetCurrentUser` are armed per test
 *     via `ArmAdminSession` / `ArmRegularSession`. This mirrors the legacy
 *     CDEMO-USER-TYPE role flag once carried in the COCOM01Y COMMAREA.
 *
 *   - `@/lib/apiClient` — everything is kept REAL through `jest.requireActual`
 *     (so the genuine `ApiError` class and the `IsApiError` guard that the
 *     page's `IsApiError(err) && err.status === 403` admin boundary depends on
 *     stay intact); ONLY `MenuApi` is stubbed, with `GetAdminMenu` armed per
 *     test. The apiClient interceptor auto-redirects on 401 only, so the 403
 *     MUST be handled by the page — asserted explicitly below.
 *
 *   - `next/link` — replaced with a lightweight anchor stand-in, because the App
 *     Router `next/link` needs the router context that is mocked here. The page
 *     renders each option as `ListItemButton component={NextLink} href={route}`,
 *     which therefore resolves to a real `<a href>` (role "link") carrying the
 *     option label; the navigation assertions read that `href`. Same proven
 *     pattern as `__tests__/components/AppShell.test.tsx`.
 *
 * SECURITY (admin gate asserted on BOTH roles): the client guard is UX only — it
 * hides the page and redirects a non-admin to /menu — while the server
 * `require_admin` boundary (HTTP 403) is authoritative. The 403 path is
 * exercised against the REAL typed `ApiError` and is also asserted to redirect.
 *
 * Ochs Test Rule: 4-space indentation, PascalCase helpers, camelCase variables,
 * ALL_UPPERCASE constants, role / label / link queries, specific jest-dom
 * matchers. No credential is ever rendered (the admin menu carries no secrets).
 */

/* ------------------------------------------------------------------------- */
/* Mock next/navigation. `mock`-prefixed jest fns are the only module-scope    */
/* names a hoisted jest.mock factory may reference. A single STABLE mockRouter */
/* object is returned so `router` identity is constant across renders.         */
/* ------------------------------------------------------------------------- */

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();

const mockRouter = {
    push: mockPush,
    replace: mockReplace,
    back: mockBack,
    refresh: mockRefresh,
    prefetch: mockPrefetch,
};

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => mockRouter,
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/admin',
}));

/* ------------------------------------------------------------------------- */
/* Mock @/lib/auth (automock). `IsAdmin` / `GetCurrentUser` become jest fns     */
/* armed per test via ArmAdminSession / ArmRegularSession.                     */
/* ------------------------------------------------------------------------- */

jest.mock('@/lib/auth');

/* ------------------------------------------------------------------------- */
/* Mock @/lib/apiClient: keep every real export (the genuine ApiError class    */
/* and IsApiError guard the 403 branch relies on) and stub ONLY MenuApi.       */
/* ------------------------------------------------------------------------- */

jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    MenuApi: { GetMenu: jest.fn(), GetAdminMenu: jest.fn() },
}));

/* ------------------------------------------------------------------------- */
/* Mock next/link. The App Router `next/link` needs the router context that is  */
/* mocked above, so it is replaced with a minimal anchor that forwards `href`   */
/* and `className` (the same proven stand-in AppShell.test.tsx uses). Because   */
/* the page renders `ListItemButton component={NextLink} href={route}` and MUI  */
/* only forces role="button" when NO href is present, this yields a real        */
/* `<a href>` with the implicit role "link". `react` is pulled in via `require` */
/* INSIDE the factory (never JSX) since a jest.mock factory may not reference   */
/* out-of-scope imports.                                                        */
/* ------------------------------------------------------------------------- */

jest.mock('next/link', () => {
    const React = require('react');
    return {
        __esModule: true,
        default: (props: {
            href: string;
            className?: string;
            children?: unknown;
        }) => {
            const resolvedHref =
                typeof props.href === 'string'
                    ? props.href
                    : String(props.href);
            return React.createElement(
                'a',
                { href: resolvedHref, className: props.className },
                props.children,
            );
        },
    };
});

import AdminPage from '@/app/admin/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    act,
    MakeAdminUser,
    MakeCurrentUser,
} from '../testUtils';
import { MenuApi, ApiError } from '@/lib/apiClient';
import { IsAdmin, GetCurrentUser } from '@/lib/auth';
import type { MenuOption, MenuResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Constants mirrored VERBATIM from the real page (frontend/src/app/admin/     */
/* page.tsx) so the spec asserts the SAME strings the component renders         */
/* (Ochs rule: ALL_UPPERCASE with underscores).                                */
/* ------------------------------------------------------------------------- */

/** Route the client admin gate AND the server 403 boundary both redirect to. */
const REDIRECT_ROUTE = '/menu';

/**
 * Page-heading fallback used when the backend omits `menu_title`. Mirrors the
 * legacy `INITIAL='Admin Menu'` label in app/bms/COADM01.bms (POS 4,35) and the
 * page's own `FALLBACK_MENU_TITLE`.
 */
const FALLBACK_MENU_TITLE = 'Admin Menu';

/** HTTP status the server `require_admin` boundary returns for a non-admin. */
const FORBIDDEN_STATUS = 403;

/** A representative non-403 failure status (exercises the error-alert branch). */
const SERVER_ERROR_STATUS = 500;

/**
 * Authoritative COBOL-program -> modern-route mapping, verified verbatim from
 * the page's `PROGRAM_ROUTE_MAP` and the backend admin menu table (COADM02Y,
 * CDEMO-ADMIN-OPT-COUNT = 4). Each tuple is
 * `[option_number, option_name, program_name, expectedHref]`; the option names
 * carry the copybook "(Security)" suffix exactly as the backend returns them.
 */
const ADMIN_OPTION_ROWS = [
    [1, 'User List (Security)', 'COUSR00C', '/users'],
    [2, 'User Add (Security)', 'COUSR01C', '/users/add'],
    [3, 'User Update (Security)', 'COUSR02C', '/users/update'],
    [4, 'User Delete (Security)', 'COUSR03C', '/users/delete'],
] as const;

/* ------------------------------------------------------------------------- */
/* Fixture factories & arrange helpers (Ochs rule: PascalCase functions,       */
/* camelCase locals, single object / no-arg signatures for the <=4-param rule).*/
/* ------------------------------------------------------------------------- */

/**
 * Build a single admin {@link MenuOption}. Defaults describe option 1
 * (User List -> COUSR00C); pass `overrides` to vary any field.
 *
 * @param overrides - Partial fields that replace any option default.
 * @returns A fully-populated MenuOption.
 */
function MakeAdminMenuOption(overrides?: Partial<MenuOption>): MenuOption {
    const baseOption: MenuOption = {
        option_number: 1,
        option_name: 'User List (Security)',
        program_name: 'COUSR00C',
        user_type: 'A',
    };

    return { ...baseOption, ...overrides };
}

/**
 * Build the full four-option admin `MenuResponse` the backend returns from
 * `GET /admin/menu`, in copybook order (COADM02Y). Pass `overrides` to adjust
 * the envelope (for example a custom `menu_title`).
 *
 * @param overrides - Partial envelope fields (for example `menu_title`).
 * @returns A fully-populated MenuResponse holding the four admin options.
 */
function MakeAdminMenuResponse(overrides?: Partial<MenuResponse>): MenuResponse {
    const menuOptions: MenuOption[] = ADMIN_OPTION_ROWS.map((row) =>
        MakeAdminMenuOption({
            option_number: row[0],
            option_name: row[1],
            program_name: row[2],
        }),
    );
    const baseResponse: MenuResponse = {
        menu_options: menuOptions,
        menu_title: FALLBACK_MENU_TITLE,
        user_type: 'A',
    };

    return { ...baseResponse, ...overrides };
}

/** A deferred promise handle for driving the pending/loading state precisely. */
interface Deferred<T> {
    promise: Promise<T>;
    Resolve: (value: T) => void;
    Reject: (reason?: unknown) => void;
}

/**
 * Create a {@link Deferred} so a test can hold `GetAdminMenu` pending (to assert
 * the loading spinner) and then settle it explicitly.
 *
 * @returns The deferred `{ promise, Resolve, Reject }` handle.
 */
function CreateDeferred<T>(): Deferred<T> {
    let resolveFn: (value: T) => void = () => undefined;
    let rejectFn: (reason?: unknown) => void = () => undefined;
    const promise = new Promise<T>((resolve, reject) => {
        resolveFn = resolve;
        rejectFn = reject;
    });

    return { promise, Resolve: resolveFn, Reject: rejectFn };
}

/** Arm the auth mocks so the current principal is an administrator ('A'). */
function ArmAdminSession(): void {
    (IsAdmin as jest.Mock).mockReturnValue(true);
    (GetCurrentUser as jest.Mock).mockReturnValue(MakeAdminUser());
}

/** Arm the auth mocks so the current principal is a regular (non-admin) user. */
function ArmRegularSession(): void {
    (IsAdmin as jest.Mock).mockReturnValue(false);
    (GetCurrentUser as jest.Mock).mockReturnValue(MakeCurrentUser());
}

/** Render the REAL AdminPage inside the shared MUI theme provider. */
function RenderAdminPage() {
    return RenderWithProviders(<AdminPage />);
}

describe('AdminPage', () => {
    beforeEach(() => {
        // `clearMocks: true` (global) clears call history before each test but
        // does NOT clear queued implementations; reset the auth + MenuApi mocks
        // so every test arms from a clean, deterministic slate.
        (IsAdmin as jest.Mock).mockReset();
        (GetCurrentUser as jest.Mock).mockReset();
        (MenuApi.GetAdminMenu as jest.Mock).mockReset();
        (MenuApi.GetMenu as jest.Mock).mockReset();
    });

    /* --------------------------------------------------------------------- */
    /* 1. Admin sees the admin menu: the four security options render as      */
    /*    navigable links with the correct hrefs, and no redirect occurs.     */
    /* --------------------------------------------------------------------- */

    it('renders the four admin options as links with correct hrefs for an admin', async () => {
        ArmAdminSession();
        (MenuApi.GetAdminMenu as jest.Mock).mockResolvedValueOnce(
            MakeAdminMenuResponse(),
        );

        RenderAdminPage();

        // Wait for the async load to resolve into the first option link.
        expect(
            await screen.findByRole('link', { name: /user list/i }),
        ).toHaveAttribute('href', '/users');

        // Every mapped option is a real <a> anchor pointing at its route.
        for (const row of ADMIN_OPTION_ROWS) {
            const optionLabel = row[1];
            const expectedHref = row[3];
            expect(
                screen.getByRole('link', { name: optionLabel }),
            ).toHaveAttribute('href', expectedHref);
        }

        // Exactly the four admin options render (no extra or missing links).
        expect(screen.getAllByRole('link')).toHaveLength(
            ADMIN_OPTION_ROWS.length,
        );

        // The admin gate passed: the load ran exactly once and no redirect fired.
        expect(MenuApi.GetAdminMenu).toHaveBeenCalledTimes(1);
        expect(mockReplace).not.toHaveBeenCalledWith(REDIRECT_ROUTE);
    });

    /* --------------------------------------------------------------------- */
    /* 2. Non-admin is redirected to /menu and never sees admin content.      */
    /* --------------------------------------------------------------------- */

    it('redirects a non-admin to /menu and renders no admin options', async () => {
        ArmRegularSession();

        RenderAdminPage();

        await waitFor(() =>
            expect(mockReplace).toHaveBeenCalledWith(REDIRECT_ROUTE),
        );

        // The admin-only content never renders for a regular user...
        expect(
            screen.queryByRole('link', { name: /user list/i }),
        ).not.toBeInTheDocument();
        // ...and the admin menu is never even fetched (client gate short-circuit).
        expect(MenuApi.GetAdminMenu).not.toHaveBeenCalled();
    });

    /* --------------------------------------------------------------------- */
    /* 3. Server 403 (require_admin) -> redirect to /menu, even though the     */
    /*    client gate passed. Exercised against the REAL typed ApiError.       */
    /* --------------------------------------------------------------------- */

    it('redirects to /menu when the server rejects GetAdminMenu with 403', async () => {
        // The client gate passes (armed admin) but the server require_admin 403s.
        ArmAdminSession();
        (MenuApi.GetAdminMenu as jest.Mock).mockRejectedValueOnce(
            new ApiError({ status: FORBIDDEN_STATUS, message: 'Forbidden' }),
        );

        RenderAdminPage();

        await waitFor(() =>
            expect(mockReplace).toHaveBeenCalledWith(REDIRECT_ROUTE),
        );
    });

    /* --------------------------------------------------------------------- */
    /* 4. Loading state: while GetAdminMenu is pending the spinner shows,      */
    /*    then it is replaced by the loaded option list once the load settles. */
    /* --------------------------------------------------------------------- */

    it('shows the loading indicator while the admin menu request is pending', async () => {
        ArmAdminSession();
        const deferred = CreateDeferred<MenuResponse>();
        (MenuApi.GetAdminMenu as jest.Mock).mockReturnValueOnce(
            deferred.promise,
        );

        RenderAdminPage();

        // Once the client gate passes the fetch is in flight...
        await waitFor(() =>
            expect(MenuApi.GetAdminMenu).toHaveBeenCalledTimes(1),
        );
        // ...and the CircularProgress (role="progressbar") is displayed.
        expect(screen.getByRole('progressbar')).toBeInTheDocument();

        // Settling the request replaces the spinner with the loaded option list.
        await act(async () => {
            deferred.Resolve(MakeAdminMenuResponse());
        });
        expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
        expect(
            screen.getByRole('link', { name: /user list/i }),
        ).toBeInTheDocument();
    });

    /* --------------------------------------------------------------------- */
    /* 5. Menu title: the server value wins; otherwise the 'Admin Menu'        */
    /*    fallback (legacy COADM01 INITIAL) is shown.                          */
    /* --------------------------------------------------------------------- */

    it('renders the server-provided menu_title when one is supplied', async () => {
        const customTitle = 'User Administration';
        ArmAdminSession();
        (MenuApi.GetAdminMenu as jest.Mock).mockResolvedValueOnce(
            MakeAdminMenuResponse({ menu_title: customTitle }),
        );

        RenderAdminPage();

        expect(
            await screen.findByRole('heading', {
                name: customTitle,
                level: 1,
            }),
        ).toBeInTheDocument();
        // Once the server title arrives, the fallback heading is gone.
        expect(
            screen.queryByRole('heading', { name: FALLBACK_MENU_TITLE }),
        ).not.toBeInTheDocument();
    });

    it('falls back to the Admin Menu title when menu_title is omitted', async () => {
        ArmAdminSession();
        (MenuApi.GetAdminMenu as jest.Mock).mockResolvedValueOnce(
            MakeAdminMenuResponse({ menu_title: undefined }),
        );

        RenderAdminPage();

        // The fallback heading is present immediately (initial state)...
        expect(
            screen.getByRole('heading', {
                name: FALLBACK_MENU_TITLE,
                level: 1,
            }),
        ).toBeInTheDocument();
        // ...and remains after the load completes because no title was sent.
        await screen.findByRole('link', { name: /user list/i });
        expect(
            screen.getByRole('heading', {
                name: FALLBACK_MENU_TITLE,
                level: 1,
            }),
        ).toBeInTheDocument();
    });

    /* --------------------------------------------------------------------- */
    /* 6. A non-403 API error surfaces an error alert and does NOT redirect.  */
    /* --------------------------------------------------------------------- */

    it('shows an error alert and does not redirect on a non-403 API error', async () => {
        ArmAdminSession();
        (MenuApi.GetAdminMenu as jest.Mock).mockRejectedValueOnce(
            new ApiError({
                status: SERVER_ERROR_STATUS,
                message: 'Admin menu service unavailable',
            }),
        );

        RenderAdminPage();

        const errorAlert = await screen.findByRole('alert');
        expect(errorAlert).toHaveTextContent('Admin menu service unavailable');
        // A non-403 failure surfaces an alert but never redirects to /menu.
        expect(mockReplace).not.toHaveBeenCalledWith(REDIRECT_ROUTE);
    });
});
