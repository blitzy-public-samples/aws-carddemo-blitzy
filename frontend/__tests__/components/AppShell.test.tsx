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
    // the rendered anchor, which the selected-route assertion relies on. It ALSO
    // forwards `onClick` (as real `next/link` does to its underlying anchor) so
    // handlers the shell attaches to nav items — notably `HandleDrawerClose`,
    // which the QA I28 drawer-focus spec exercises — actually fire on click.
    const React = require('react');
    return {
        __esModule: true,
        default: (props: {
            href: string;
            className?: string;
            onClick?: (event: unknown) => void;
            onMouseEnter?: (event: unknown) => void;
            onFocus?: (event: unknown) => void;
            prefetch?: boolean | null;
            children?: unknown;
        }) => {
            const resolvedHref =
                typeof props.href === 'string' ? props.href : String(props.href);
            return React.createElement(
                'a',
                {
                    href: resolvedHref,
                    className: props.className,
                    onClick: props.onClick,
                    // Real next/link forwards these intent handlers to its
                    // underlying anchor; the shell uses them to latch a nav
                    // destination for prefetch (QA I26), so the mock must too.
                    onMouseEnter: props.onMouseEnter,
                    onFocus: props.onFocus,
                    // Real next/link CONSUMES `prefetch` and never forwards it to
                    // the DOM anchor; the mock mirrors that (so React emits no
                    // "unknown DOM prop" warning) but records the current value
                    // as a data-* attribute so the QA I26 selective-prefetch spec
                    // can assert it flips from "false" (disabled) to "null"
                    // (next/link default) once the user shows intent.
                    'data-prefetch': String(props.prefetch),
                },
                props.children,
            );
        },
    };
});

import {
    RenderWithProviders,
    screen,
    userEvent,
    fireEvent,
    waitFor,
    act,
    MakeCurrentUser,
    MakeAdminUser,
} from '../testUtils';
import type { ReactElement } from 'react';
import { AppShell, IsInsideAriaHidden, FocusWhenExposed } from '@/components/AppShell';
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
        // Logout now RESOLVES only after confirmed server revocation (QA Issue 2);
        // default it to a resolved promise so HandleLogout's `.catch().finally()`
        // chain has a thenable to attach to. Failure-path specs override this.
        mockLogout.mockResolvedValue(undefined);
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
    // Selective RSC prefetch (QA I26)
    // ----------------------------------------------------------------------
    // next/link eagerly prefetches every in-viewport <Link> by default, so the
    // permanent desktop drawer would prefetch ALL nav destinations' RSC payloads
    // on every page load. The shell instead starts each nav link with
    // prefetch={false} (App Router: no viewport OR hover auto-prefetch) and opts
    // a destination into next/link's default (prefetch={null}) only once the user
    // shows intent toward it via hover or keyboard focus. The mocked next/link
    // exposes the live prefetch value as `data-prefetch` ("false" = disabled,
    // "null" = next/link default/enabled) for these assertions.

    it('starts every nav link with prefetch disabled on load (QA I26)', () => {
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // Nothing is prefetched until the user shows intent, so every nav link
        // renders with the disabled sentinel — the eager viewport prefetch of
        // all destinations that I26 reported is gone.
        REGULAR_NAV_LABELS.forEach((label) => {
            expect(
                screen.getByRole('link', { name: label }),
            ).toHaveAttribute('data-prefetch', 'false');
        });
    });

    it('enables prefetch for a nav link only after hover intent, leaving the rest disabled (QA I26)', async () => {
        const user = userEvent.setup();
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        const accountsLink = screen.getByRole('link', { name: 'Accounts' });
        expect(accountsLink).toHaveAttribute('data-prefetch', 'false');

        await user.hover(accountsLink);

        // The hovered destination latches into next/link's default prefetch...
        expect(
            screen.getByRole('link', { name: 'Accounts' }),
        ).toHaveAttribute('data-prefetch', 'null');
        // ...while every OTHER destination stays disabled (selective, not the
        // global eager prefetch the finding reported).
        REGULAR_NAV_LABELS.filter((label) => label !== 'Accounts').forEach(
            (label) => {
                expect(
                    screen.getByRole('link', { name: label }),
                ).toHaveAttribute('data-prefetch', 'false');
            },
        );
    });

    it('enables prefetch for a nav link on keyboard focus intent (QA I26)', () => {
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        const cardsLink = screen.getByRole('link', { name: 'Cards' });
        expect(cardsLink).toHaveAttribute('data-prefetch', 'false');

        // Keyboard users (Tab-to-focus) get the same prefetch-on-intent as mouse
        // users. React derives onFocus from the bubbling focusin event.
        fireEvent.focusIn(cardsLink);

        expect(
            screen.getByRole('link', { name: 'Cards' }),
        ).toHaveAttribute('data-prefetch', 'null');
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
    // Client-side auth guard (dest QA F-1)
    // ----------------------------------------------------------------------

    it('redirects an unauthenticated visitor on a protected route to /signon and never leaves protected content on screen (dest F-1)', async () => {
        const replace = jest.fn();
        mockUsePathname.mockReturnValue('/accounts/view');
        mockUseRouter.mockReturnValue(MakeRouterMock({ replace }));
        // No client identity => the mount-time guard must redirect to /signon.
        mockGetCurrentUser.mockReturnValue(null);

        RenderWithProviders(
            <AppShell>
                <div>Protected Content</div>
            </AppShell>,
        );

        // The guard effect redirects to the signon route...
        await waitFor(() => {
            expect(replace).toHaveBeenCalledWith(SIGNON_ROUTE);
        });
        // ...and the protected page body is never left on screen (an empty
        // <main> placeholder renders while the redirect settles), with no shell
        // chrome (AppBar / nav) exposed to an unauthenticated visitor.
        expect(screen.queryByText('Protected Content')).not.toBeInTheDocument();
        expect(screen.queryByRole('banner')).not.toBeInTheDocument();
        expect(
            screen.queryByRole('link', { name: 'Menu' }),
        ).not.toBeInTheDocument();
    });

    it('does not redirect an unauthenticated visitor on the signon route so login stays reachable (dest F-1)', async () => {
        const replace = jest.fn();
        mockUsePathname.mockReturnValue(SIGNON_ROUTE);
        mockUseRouter.mockReturnValue(MakeRouterMock({ replace }));
        mockGetCurrentUser.mockReturnValue(null);

        RenderWithProviders(
            <AppShell>
                <div>Signon Page</div>
            </AppShell>,
        );

        // The signon route is exempt from the guard: the bare login page renders
        // and no redirect is issued (a redirect here would loop).
        expect(screen.getByText('Signon Page')).toBeInTheDocument();
        await waitFor(() => {
            expect(mockGetCurrentUser).toHaveBeenCalled();
        });
        expect(replace).not.toHaveBeenCalled();
    });

    it('does not redirect an authenticated visitor on a protected route (dest F-1 guard no-op)', async () => {
        const replace = jest.fn();
        mockUsePathname.mockReturnValue(MENU_ROUTE);
        mockUseRouter.mockReturnValue(MakeRouterMock({ replace }));
        // An established identity must never be redirected by the guard.
        mockGetCurrentUser.mockReturnValue(MakeCurrentUser());

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        await waitFor(() => {
            expect(screen.getByText('Page Body')).toBeInTheDocument();
        });
        expect(replace).not.toHaveBeenCalled();
    });

    // ----------------------------------------------------------------------
    // Route-change focus management (QA N-02)
    // ----------------------------------------------------------------------

    it('moves focus to the new page heading on a client-side route change (N-02)', async () => {
        // Initial route /menu: the announcer must NOT steal focus on first paint
        // (it acts only on SUBSEQUENT navigations), so the heading is unfocused.
        mockUsePathname.mockReturnValue(MENU_ROUTE);

        const { rerender } = RenderWithProviders(
            <AppShell>
                <h1>Main Menu</h1>
            </AppShell>,
        );

        expect(
            screen.getByRole('heading', { level: 1, name: 'Main Menu' }),
        ).not.toHaveFocus();

        // Simulate a client-side navigation to /cards with a new page heading.
        mockUsePathname.mockReturnValue('/cards');
        rerender(
            <AppShell>
                <h1>List Credit Cards</h1>
            </AppShell>,
        );

        // The new page's <h1> receives focus (made programmatically focusable via
        // tabindex="-1") so assistive tech announces the new page.
        await waitFor(() => {
            expect(
                screen.getByRole('heading', {
                    level: 1,
                    name: 'List Credit Cards',
                }),
            ).toHaveFocus();
        });
    });

    it('leaves focus on a page control that autofocused on navigation (N-02)', async () => {
        mockUsePathname.mockReturnValue(MENU_ROUTE);

        const { rerender } = RenderWithProviders(
            <AppShell>
                <h1>Main Menu</h1>
            </AppShell>,
        );

        // Navigate to a page whose primary input autofocuses. React applies
        // autoFocus during commit (before the announcer's passive effect), so the
        // announcer must find focus already inside <main> and leave it there
        // rather than yanking focus to the heading.
        mockUsePathname.mockReturnValue('/accounts/update');
        rerender(
            <AppShell>
                <h1>Update Account</h1>
                <input aria-label="Account Number" autoFocus />
            </AppShell>,
        );

        await waitFor(() => {
            expect(screen.getByLabelText('Account Number')).toHaveFocus();
        });
        expect(
            screen.getByRole('heading', { level: 1, name: 'Update Account' }),
        ).not.toHaveFocus();
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

    it('keeps the user signed in and surfaces a retryable failure when sign-out is not confirmed (QA Issue 2 / CWE-613)', async () => {
        const user = userEvent.setup();
        const replace = jest.fn();
        mockUseRouter.mockReturnValue(MakeRouterMock({ replace }));
        // Server revocation could not be confirmed: `Logout` rejects (it performs
        // no teardown/redirect). The shell must NOT present a signed-out state.
        mockLogout.mockRejectedValue(
            new Error('Sign-out could not be confirmed by the server.'),
        );

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        await user.click(screen.getByRole('button', { name: /logout/i }));

        // The explicit failure alert appears...
        await waitFor(() => {
            expect(screen.getByText('Sign-out failed')).toBeInTheDocument();
        });
        // ...the user is NOT signed out: the shell chrome and page remain, and no
        // signon redirect was issued.
        expect(screen.getByText('Page Body')).toBeInTheDocument();
        expect(screen.getByRole('banner')).toBeInTheDocument();
        expect(replace).not.toHaveBeenCalledWith(SIGNON_ROUTE);
    });

    it('disables the Logout control while sign-out is in flight (QA Issue 2)', async () => {
        const user = userEvent.setup();
        // A pending revocation keeps the button in its in-flight state so a
        // second click cannot start an overlapping sign-out attempt.
        let resolveLogout: () => void = () => {};
        mockLogout.mockReturnValue(
            new Promise<void>((resolve) => {
                resolveLogout = resolve;
            }),
        );

        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        await user.click(screen.getByRole('button', { name: /logout/i }));

        await waitFor(() => {
            expect(
                screen.getByRole('button', { name: /signing out/i }),
            ).toBeDisabled();
        });

        // Settle the pending promise so no state update lands after the test.
        await act(async () => {
            resolveLogout();
        });
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

    // ----------------------------------------------------------------------
    // Mobile drawer close moves focus out of the closing subtree (QA I28)
    // ----------------------------------------------------------------------

    it('moves focus off the chosen nav item before the drawer closes (QA I28)', async () => {
        RenderWithProviders(
            <AppShell>
                <div>Page Body</div>
            </AppShell>,
        );

        // With the mobile drawer shut, exactly one accessible nav anchor exists
        // (the permanent drawer's; the temporary drawer is aria-hidden). Every
        // nav item's onClick is HandleDrawerClose, so this exercises the very
        // handler the mobile overlay dismissal uses.
        const cardsLink = screen.getByRole('link', { name: 'Cards' });

        // Neutralize jsdom's unimplemented anchor navigation WITHOUT touching the
        // React onClick — preventDefault blocks only the default action, so
        // HandleDrawerClose still runs.
        cardsLink.addEventListener('click', (event) => event.preventDefault());

        // Observe the fix's mechanism directly: HandleDrawerClose blurs the
        // active element. With the mobile drawer already shut, activating this
        // (permanent-drawer) item is a `setMobileOpen(false)` no-op — no MUI
        // Modal opens or closes — so the ONLY thing that can move focus is the
        // handler's explicit blur. That isolation makes the assertions a clean
        // differential: remove the blur and focus stays on the item.
        const blurSpy = jest.spyOn(cardsLink, 'blur');

        // Put focus ON the nav item, as a keyboard/touch user would before
        // activating it. MUI ButtonBase flushes focus-visible state on focus, so
        // wrap the focus call in act.
        await act(async () => {
            cardsLink.focus();
        });
        expect(document.activeElement).toBe(cardsLink);

        // Choosing the destination closes the drawer. Before the QA I28 fix the
        // just-clicked item KEPT focus while MUI's Modal applied `aria-hidden`
        // to its ancestor, logging the "aria-hidden on an ancestor of a focused
        // element" warning. The fix blurs the active element FIRST, so focus
        // returns to <body> — outside the closing drawer subtree. A low-level
        // `fireEvent.click` measures the component's own focus handling
        // (HandleDrawerClose) rather than userEvent's focus choreography, which
        // would otherwise re-focus the anchor after the handler runs.
        await act(async () => {
            fireEvent.click(cardsLink);
        });

        expect(blurSpy).toHaveBeenCalled();
        expect(document.activeElement).not.toBe(cardsLink);
        expect(document.activeElement).toBe(document.body);
    });

    it('withholds the hamburger-toggle focus hand-off until the app root is no longer aria-hidden, then focuses it (QA I28)', async () => {
        // Regression for the QA I28 RUNTIME finding. Dismissing the temporary
        // drawer WITHOUT navigating (Escape / backdrop tap) must return focus to
        // the hamburger toggle — but NEVER while MUI still has `aria-hidden="true"`
        // on the app root, which it keeps for the WHOLE slide-out transition (the
        // first fix attempt re-focused the toggle one frame after close, ~200 ms
        // too early, and Chrome logged "Blocked aria-hidden … descendant retained
        // focus"). The shell now (a) disables MUI's own focus-restore
        // (`disableRestoreFocus`) and (b) hands focus back via `FocusWhenExposed`,
        // which polls animation frames until no `aria-hidden` ancestor remains.
        //
        // This spec proves both halves deterministically: with a captured rAF
        // queue it shows focus is WITHHELD frame after frame while the app root is
        // aria-hidden, and only lands on the toggle once the ancestor `aria-hidden`
        // is removed (mirroring MUI's `ModalManager.remove` at transition end).
        const rafCallbacks: FrameRequestCallback[] = [];
        const rafSpy = jest
            .spyOn(window, 'requestAnimationFrame')
            .mockImplementation((cb: FrameRequestCallback): number => {
                rafCallbacks.push(cb);
                return rafCallbacks.length;
            });

        /** Runs every currently-queued rAF callback once (they may re-queue). */
        function RunOneFrame(): void {
            const pending = rafCallbacks.splice(0, rafCallbacks.length);
            pending.forEach((callback) => callback(0));
        }

        /**
         * Mirrors MUI's `ModalManager.remove`: strips `aria-hidden="true"` from
         * the toggle and every ancestor, i.e. re-exposes the app root exactly as
         * the Drawer's slide-out completion does.
         */
        function ExposeAppRoot(element: HTMLElement): void {
            let node: HTMLElement | null = element;
            while (node !== null) {
                if (node.getAttribute('aria-hidden') === 'true') {
                    node.removeAttribute('aria-hidden');
                }
                node = node.parentElement;
            }
        }

        try {
            RenderWithProviders(
                <AppShell>
                    <div>Page Body</div>
                </AppShell>,
            );

            // Open the temporary (mobile) drawer from the hamburger toggle. In
            // jsdom the `display: { md: 'none' }` sx is inert, so the toggle is
            // present and clickable regardless of viewport. Opening it makes MUI's
            // Modal put `aria-hidden="true"` on the app-root subtree (which
            // contains the toggle).
            const toggle = screen.getByRole('button', {
                name: 'Open navigation menu',
            });
            await act(async () => {
                fireEvent.click(toggle);
            });

            // The open temporary drawer is a MUI Modal exposing role="presentation"
            // on its root; MUI's Escape handler is bound there.
            const presentation = await screen.findByRole('presentation');
            // Sanity: the toggle really is inside an aria-hidden subtree now, so
            // the deferral below is exercising the real condition.
            expect(toggle.closest('[aria-hidden="true"]')).not.toBeNull();

            // Dismiss with Escape (a NON-navigating dismissal). MUI's Modal fires
            // `onClose`, which the shell routes through
            // HandleDrawerClose({ returnFocusToToggle: true }); that blurs to
            // <body> and schedules the aria-hidden-aware hand-off.
            await act(async () => {
                fireEvent.keyDown(presentation, { key: 'Escape', code: 'Escape' });
            });

            // While the app root stays aria-hidden, the hand-off must keep
            // deferring: focus never lands on the toggle no matter how many frames
            // elapse. Run several frames and assert the toggle is still unfocused.
            await act(async () => {
                RunOneFrame();
                RunOneFrame();
                RunOneFrame();
            });
            expect(toggle).not.toHaveFocus();
            expect(toggle.closest('[aria-hidden="true"]')).not.toBeNull();

            // The slide-out completes: MUI removes `aria-hidden` from the app root.
            act(() => {
                ExposeAppRoot(toggle);
            });

            // The very next frame now finds the toggle exposed and focuses it.
            await act(async () => {
                RunOneFrame();
            });
            expect(toggle).toHaveFocus();
        } finally {
            rafSpy.mockRestore();
        }
    });
});

/* ------------------------------------------------------------------------- */
/* Focus helpers (QA I28) — direct unit coverage of the exported utilities.   */
/* ------------------------------------------------------------------------- */

describe('IsInsideAriaHidden', () => {
    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('returns true when the element itself is aria-hidden', () => {
        const element = document.createElement('button');
        element.setAttribute('aria-hidden', 'true');
        document.body.appendChild(element);

        expect(IsInsideAriaHidden(element)).toBe(true);
    });

    it('returns true when an ANCESTOR is aria-hidden', () => {
        const ancestor = document.createElement('div');
        ancestor.setAttribute('aria-hidden', 'true');
        const child = document.createElement('button');
        ancestor.appendChild(child);
        document.body.appendChild(ancestor);

        expect(IsInsideAriaHidden(child)).toBe(true);
    });

    it('returns false when neither the element nor any ancestor is aria-hidden', () => {
        const parent = document.createElement('div');
        const child = document.createElement('button');
        parent.appendChild(child);
        document.body.appendChild(parent);

        expect(IsInsideAriaHidden(child)).toBe(false);
    });

    it('ignores aria-hidden="false" (only "true" hides)', () => {
        const ancestor = document.createElement('div');
        ancestor.setAttribute('aria-hidden', 'false');
        const child = document.createElement('button');
        ancestor.appendChild(child);
        document.body.appendChild(ancestor);

        expect(IsInsideAriaHidden(child)).toBe(false);
    });
});

describe('FocusWhenExposed', () => {
    let rafCallbacks: FrameRequestCallback[];
    let rafSpy: jest.SpyInstance;

    beforeEach(() => {
        rafCallbacks = [];
        rafSpy = jest
            .spyOn(window, 'requestAnimationFrame')
            .mockImplementation((cb: FrameRequestCallback): number => {
                rafCallbacks.push(cb);
                return rafCallbacks.length;
            });
    });

    afterEach(() => {
        rafSpy.mockRestore();
        document.body.innerHTML = '';
    });

    /** Runs every currently-queued rAF callback once (they may re-queue). */
    function RunOneFrame(): void {
        const pending = rafCallbacks.splice(0, rafCallbacks.length);
        pending.forEach((callback) => callback(0));
    }

    it('is a no-op for a null target and never schedules a frame', () => {
        expect(() => FocusWhenExposed(null)).not.toThrow();
        expect(rafCallbacks).toHaveLength(0);
    });

    it('focuses SYNCHRONOUSLY when the target is already exposed (no deferral)', () => {
        const element = document.createElement('button');
        document.body.appendChild(element);

        FocusWhenExposed(element);

        // Focused immediately, without waiting for a frame.
        expect(element).toHaveFocus();
        expect(rafCallbacks).toHaveLength(0);
    });

    it('withholds focus while an ancestor is aria-hidden, then focuses once exposed', () => {
        const ancestor = document.createElement('div');
        ancestor.setAttribute('aria-hidden', 'true');
        const element = document.createElement('button');
        ancestor.appendChild(element);
        document.body.appendChild(ancestor);

        FocusWhenExposed(element);

        // Deferred: not focused, a frame is queued.
        expect(element).not.toHaveFocus();
        expect(rafCallbacks.length).toBeGreaterThan(0);

        // Still hidden across several frames -> still not focused.
        RunOneFrame();
        RunOneFrame();
        expect(element).not.toHaveFocus();

        // Expose the subtree (mirrors MUI removing app-root aria-hidden).
        ancestor.removeAttribute('aria-hidden');
        RunOneFrame();

        expect(element).toHaveFocus();
    });

    it('focuses eventually even if the subtree never exposes (bounded, never spins)', () => {
        const ancestor = document.createElement('div');
        ancestor.setAttribute('aria-hidden', 'true');
        const element = document.createElement('button');
        ancestor.appendChild(element);
        document.body.appendChild(ancestor);

        FocusWhenExposed(element);
        expect(element).not.toHaveFocus();

        // Drain far more frames than the internal cap (30) WITHOUT ever exposing;
        // the helper must stop deferring and focus so it can never loop forever.
        for (let frame = 0; frame < 40; frame += 1) {
            RunOneFrame();
        }

        expect(element).toHaveFocus();
    });
});
