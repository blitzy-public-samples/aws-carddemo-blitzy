/**
 * AppShell.test.tsx — React Testing Library spec for the application shell
 * `@/components/AppShell` (MUI AppBar + Drawer + List). It verifies navigation
 * rendering, role-based visibility (the admin-only Admin/Users destinations are
 * hidden for a regular `user_type='U'` user and shown for `user_type='A'`), the
 * `/signon` self-bypass (bare children, no chrome), the SSR-safe post-mount auth
 * read (`useEffect`), Logout, and the legacy PF3→Escape back mapping. The shell
 * is the modern replacement for the 3270 menu-driven navigation of the regular
 * menu `COMEN01` (app/cpy-bms/COMEN01.CPY) and admin menu `COADM01`
 * (app/cpy-bms/COADM01.CPY). Greenfield test infra — no legacy test origin.
 */

/* --------------------------------------------------------------------------- */
/* Module mocks (hoisted by jest above the imports below). The shell depends on */
/* client-only auth state and the Next App Router, neither of which exists in   */
/* jsdom, so all three collaborators are mocked. See the factory-scope rule     */
/* note on `next/link`.                                                         */
/* --------------------------------------------------------------------------- */

jest.mock('@/lib/auth');

jest.mock('next/navigation', () => ({
    usePathname: jest.fn(),
    useRouter: jest.fn(),
}));

jest.mock('next/link', () => {
    // A `jest.mock` factory may reference only `mock`-prefixed out-of-scope names
    // or Node globals, so `react` is pulled in via `require` INSIDE the factory
    // (never JSX). The mock forwards `href` AND `className` so the MUI
    // `Mui-selected` class that `ListItemButton component={Link}` computes reaches
    // the rendered anchor, which the selected-route assertion relies on.
    const React = require('react');
    return {
        __esModule: true,
        default: (props: { href: string; className?: string; children?: unknown }) => {
            const resolvedHref =
                typeof props.href === 'string' ? props.href : String(props.href);
            return React.createElement(
                'a',
                { href: resolvedHref, className: props.className },
                props.children,
            );
        },
    };
});

import {
    RenderWithProviders,
    screen,
    userEvent,
    waitFor,
    act,
    MakeCurrentUser,
    MakeAdminUser,
} from '../testUtils';
import type { ReactElement } from 'react';
import { AppShell } from '@/components/AppShell';
import { GetCurrentUser, IsAdmin, Logout } from '@/lib/auth';
import { usePathname, useRouter } from 'next/navigation';

/* --------------------------------------------------------------------------- */
/* Typed mock handles (identity-stable across tests — `clearMocks: true` wipes   */
/* their call history but never the underlying `jest.fn` objects). Declared at   */
/* module scope; the `mock`-prefix keeps them usable inside the mock factories.  */
/* --------------------------------------------------------------------------- */

const mockUsePathname = usePathname as jest.MockedFunction<typeof usePathname>;
const mockUseRouter = useRouter as jest.MockedFunction<typeof useRouter>;
const mockGetCurrentUser = GetCurrentUser as jest.MockedFunction<typeof GetCurrentUser>;
const mockIsAdmin = IsAdmin as jest.MockedFunction<typeof IsAdmin>;
const mockLogout = Logout as jest.MockedFunction<typeof Logout>;

/* --------------------------------------------------------------------------- */
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores).                  */
/* --------------------------------------------------------------------------- */

/** Regular-user navigation labels always visible regardless of role. */
const REGULAR_NAV_LABELS = [
    'Menu',
    'Accounts',
    'Cards',
    'Transactions',
    'Reports',
    'Bill Pay',
];

/** Admin-only navigation labels gated behind `user_type === 'A'`. */
const ADMIN_NAV_LABELS = ['Admin', 'Users'];

/** The pre-authentication signon route the shell renders without chrome. */
const SIGNON_ROUTE = '/signon';

/** A default authenticated route used for the majority of the specs. */
const MENU_ROUTE = '/menu';

/* --------------------------------------------------------------------------- */
/* Test helpers (Ochs rule: PascalCase functions, single object parameter).     */
/* --------------------------------------------------------------------------- */

/** Optional per-test overrides for the fully-shaped App Router mock. */
interface RouterOverrides {
    push?: jest.Mock;
    back?: jest.Mock;
    replace?: jest.Mock;
    forward?: jest.Mock;
    refresh?: jest.Mock;
    prefetch?: jest.Mock;
}

/**
 * Builds a fully-shaped App Router mock so any internal `router.*` call the shell
 * makes (`push` for the brand, `back` for the Escape handler) resolves to a
 * `jest.fn` rather than throwing. Pass `overrides` to capture a specific method
 * (e.g. a spy `back`) for assertions.
 *
 * @param overrides - Partial map of router methods to substitute.
 * @returns A router object cast to the App Router instance type.
 */
function MakeRouterMock(overrides: RouterOverrides = {}): ReturnType<typeof useRouter> {
    const routerBase = {
        push: jest.fn(),
        back: jest.fn(),
        replace: jest.fn(),
        forward: jest.fn(),
        refresh: jest.fn(),
        prefetch: jest.fn(),
    };
    return { ...routerBase, ...overrides } as unknown as ReturnType<typeof useRouter>;
}

describe('AppShell', () => {
    // `clearMocks: true` clears call history before each test but NOT the mock
    // implementations, so every return value is re-established here to keep the
    // suite deterministic. The default principal is a regular ('U') user, which
    // is REQUIRED: the shell bypasses its chrome when it is mounted with no
    // current user, so an authenticated fixture must be present by default.
    beforeEach(() => {
        mockUsePathname.mockReturnValue(MENU_ROUTE);
        mockUseRouter.mockReturnValue(MakeRouterMock());
        mockGetCurrentUser.mockReturnValue(MakeCurrentUser());
        mockIsAdmin.mockReturnValue(false);
    });

    // ----------------------------------------------------------------------
    // Basic render
    // ----------------------------------------------------------------------

    it('renders the page children inside the main region', () => {
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        expect(screen.getByText('Page Body')).toBeInTheDocument();
    });

    it('renders the application title bar', () => {
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // MUI `AppBar` renders a `<header>` whose implicit landmark role is
        // `banner`; the brand text comes from `app/cpy/COTTL01Y.cpy`.
        expect(screen.getByRole('banner')).toBeInTheDocument();
        expect(screen.getByText('CardDemo')).toBeInTheDocument();
    });

    it('renders the navigation drawer with the regular-user items', () => {
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // Each nav entry renders as the mocked `next/link` anchor; the
        // `ListItemText` primary text is its accessible name. (The label text
        // appears twice in the DOM — one per drawer — but exactly one anchor is
        // exposed to the accessibility tree, so a role query resolves uniquely.)
        REGULAR_NAV_LABELS.forEach((label) => {
            expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
        });
    });

    // ----------------------------------------------------------------------
    // Role-based navigation (COMEN01 regular menu vs COADM01 admin menu)
    // ----------------------------------------------------------------------

    it('hides the admin-only items for a regular user (user_type=U)', () => {
        mockGetCurrentUser.mockReturnValue(MakeCurrentUser());
        mockIsAdmin.mockReturnValue(false);

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        ADMIN_NAV_LABELS.forEach((label) => {
            expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument();
        });
        // The regular destinations must still be present for the 'U' user.
        REGULAR_NAV_LABELS.forEach((label) => {
            expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
        });
    });

    it('shows the admin-only items for an administrator (user_type=A)', async () => {
        mockGetCurrentUser.mockReturnValue(MakeAdminUser());
        mockIsAdmin.mockReturnValue(true);

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // Admin visibility is driven by post-mount `IsAdmin()` state, so wait for
        // the effect to promote the admin destinations into the drawer.
        await waitFor(() => {
            expect(screen.getByRole('link', { name: 'Admin' })).toBeInTheDocument();
        });
        expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument();
        // The regular destinations remain alongside the admin ones.
        REGULAR_NAV_LABELS.forEach((label) => {
            expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
        });
    });

    it('marks the current route as the selected nav item', () => {
        mockUsePathname.mockReturnValue('/cards');

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // MUI applies the `Mui-selected` class to the active `ListItemButton`,
        // which — rendered `component={Link}` — is the anchor element itself.
        const cardsLink = screen.getByRole('link', { name: 'Cards' });
        expect(cardsLink.className).toMatch(/Mui-selected/);
    });

    // ----------------------------------------------------------------------
    // /signon self-bypass (COSGN00 renders without the shell chrome)
    // ----------------------------------------------------------------------

    it('renders bare children on the signon route without any chrome', () => {
        mockUsePathname.mockReturnValue(SIGNON_ROUTE);

        RenderWithProviders(
            <AppShell>
                <div>Signon Page</div>
            </AppShell>,
        );

        expect(screen.getByText('Signon Page')).toBeInTheDocument();
        // No AppBar, no brand, and no navigation are rendered on the signon page.
        expect(screen.queryByRole('banner')).not.toBeInTheDocument();
        expect(screen.queryByText('CardDemo')).not.toBeInTheDocument();
        expect(screen.queryByRole('link', { name: 'Menu' })).not.toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // Post-mount auth read + Logout + PF3/Escape mapping
    // ----------------------------------------------------------------------

    it('reads the current user after mount and shows the identity', async () => {
        mockGetCurrentUser.mockReturnValue(
            MakeCurrentUser({ first_name: 'Test', last_name: 'User', user_type: 'U' }),
        );

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        expect(mockGetCurrentUser).toHaveBeenCalled();
        // Identity is read inside `useEffect`, so the toolbar label
        // (`${first} ${last} (${type})`) appears after the effect runs.
        await waitFor(() => {
            expect(screen.getByText(/Test User/)).toBeInTheDocument();
        });
        expect(screen.getByText(/\(U\)/)).toBeInTheDocument();
    });

    it('calls Logout when the logout button is clicked', async () => {
        const user = userEvent.setup();

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        await user.click(screen.getByRole('button', { name: /logout/i }));

        expect(mockLogout).toHaveBeenCalledTimes(1);
    });

    it('navigates back when Escape is pressed (legacy PF3 mapping)', async () => {
        const user = userEvent.setup();
        const back = jest.fn();
        mockUseRouter.mockReturnValue(MakeRouterMock({ back }));

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // Escape must bubble from a focused descendant to the shell's key handler.
        // The focus call is wrapped in `act` because MUI's ButtonBase updates its
        // focus-visible state on focus, and that update must be flushed inside act.
        const logoutButton = await screen.findByRole('button', { name: /logout/i });
        await act(async () => {
            logoutButton.focus();
        });
        await user.keyboard('{Escape}');

        expect(back).toHaveBeenCalledTimes(1);
    });

    it('stands down when a descendant already handled Escape (single owner, M-28)', async () => {
        const user = userEvent.setup();
        const back = jest.fn();
        mockUseRouter.mockReturnValue(MakeRouterMock({ back }));

        // A descendant that OWNS Escape for its own context calls
        // preventDefault. The shell is the single global fallback owner and must
        // stand down so Escape is not handled twice (QA M-28 double navigation).
        function EscapeOwner(): ReactElement {
            return (
                <button
                    type="button"
                    onKeyDown={(event) => {
                        if (event.key === 'Escape') {
                            event.preventDefault();
                        }
                    }}
                >
                    Owns Escape
                </button>
            );
        }

        RenderWithProviders(
            <AppShell>
                <EscapeOwner />
            </AppShell>,
        );

        const owner = screen.getByRole('button', { name: 'Owns Escape' });
        await act(async () => {
            owner.focus();
        });
        await user.keyboard('{Escape}');

        expect(back).not.toHaveBeenCalled();
    });
});
