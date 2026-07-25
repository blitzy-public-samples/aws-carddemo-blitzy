/** User List page spec — legacy origin BMS COUSR00 / Tx CU00 / program COUSR00C. Admin-only. */

/*
 * Component/integration spec for the modern User List page (route `/users`),
 * the greenfield replacement for the legacy 3270 admin browse screen COUSR00
 * (CICS transaction CU00, COBOL program COUSR00C). Source of truth for the
 * legacy layout: app/bms/COUSR00.bms + app/cpy-bms/COUSR00.CPY.
 *
 * The real page is rendered with the API layer (`@/lib/apiClient` -> UsersApi),
 * the identity helpers (`@/lib/auth`), and the App Router hooks
 * (`next/navigation`) mocked, so no network request is ever made. `ApiError`
 * and `IsApiError` are kept REAL (via `jest.requireActual`) so the page's
 * `IsApiError(err) && err.status === 403` admin-boundary branch is exercised
 * against the genuine typed error, not a stand-in.
 *
 * Ochs Test Rule: 4-space indentation, PascalCase helpers, camelCase variables,
 * ALL_UPPERCASE constants, role/label queries, and specific jest-dom matchers.
 * UserSummary carries no password, so no credential ever appears in the UI.
 */

import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    SetupUser,
    MakePaginatedResponse,
    MakeAdminUser,
    MakeCurrentUser,
    MakeUserSummary,
} from '../testUtils';
import UsersPage from '@/app/users/page';
import { UsersApi, ApiError } from '@/lib/apiClient';
import { IsAdmin, GetCurrentUser } from '@/lib/auth';
import { DEFAULT_PAGE_SIZE } from '@/types';
import type { PaginatedResponse, UserSummary } from '@/types';

// ---------------------------------------------------------------------------
// next/navigation mock. The App Router hooks are replaced with `mock`-prefixed
// jest fns (the only names a jest factory may reference from module scope). A
// STABLE `mockRouter` object is returned from `useRouter` so the page's
// `useCallback`/`useEffect` dependencies (which include `router`) stay
// referentially stable across renders — otherwise `ListUsers` would refetch on
// every render and break the call-count assertions.
// ---------------------------------------------------------------------------

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
    usePathname: () => '/users',
}));

// ---------------------------------------------------------------------------
// @/lib/auth mock (automock). `IsAdmin` and `GetCurrentUser` become jest fns
// whose return value each test arms via `ArmAdminSession` / `ArmRegularSession`.
// ---------------------------------------------------------------------------

jest.mock('@/lib/auth');

// ---------------------------------------------------------------------------
// @/lib/apiClient partial mock. Everything real EXCEPT `UsersApi`, whose methods
// become jest fns. `ApiError` / `IsApiError` remain the genuine implementations
// so the 403 -> /menu branch narrows on a real typed error instance.
// ---------------------------------------------------------------------------

jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    UsersApi: {
        ListUsers: jest.fn(),
        AddUser: jest.fn(),
        GetUser: jest.fn(),
        UpdateUser: jest.fn(),
        DeleteUser: jest.fn(),
    },
}));

// ---------------------------------------------------------------------------
// Fixed test constants (ALL_UPPERCASE per the Ochs Rule).
// ---------------------------------------------------------------------------

/** Route the admin gate (client) and the server 403 boundary both redirect to. */
const MENU_ROUTE = '/menu';

/** Route the "Add User" action pushes to (COUSR01 / CU01). */
const ADD_USER_ROUTE = '/users/add';

/** Seed-style regular user id (README sample) used across fixtures. */
const REGULAR_USER_ID = 'USER0001';

/** Seed-style administrator user id (README sample) used across fixtures. */
const ADMIN_USER_ID = 'ADMIN001';

/** Visible label of the search field (drives the accessible-name query). */
const SEARCH_LABEL = 'Search User ID';

/** Copybook width of the user-id search field (COUSR00 USRIDINI PIC X(8)). */
const SEARCH_MAX_LENGTH = 8;

/** HTTP status the server require_admin boundary returns for non-admin callers. */
const FORBIDDEN_STATUS = 403;

/** A representative non-403 failure status (exercises the error-alert branch). */
const SERVER_ERROR_STATUS = 500;

/** The COUSR00 column headers, in order, mirrored by the page's ColumnDef set. */
const COLUMN_HEADERS = [
    'User ID',
    'First Name',
    'Last Name',
    'Type',
    'Actions',
] as const;

/** Encoded per-row Update route the page pushes for `REGULAR_USER_ID`. */
const UPDATE_ROUTE = `/users/update?userId=${encodeURIComponent(REGULAR_USER_ID)}`;

/** Encoded per-row Delete route the page pushes for `REGULAR_USER_ID`. */
const DELETE_ROUTE = `/users/delete?userId=${encodeURIComponent(REGULAR_USER_ID)}`;

// ---------------------------------------------------------------------------
// Arrange helpers (PascalCase per the Ochs Rule; single-object / no-arg so the
// <=4-parameter rule holds).
// ---------------------------------------------------------------------------

/** Arm the auth mocks so the current principal is an administrator. */
function ArmAdminSession(): void {
    (IsAdmin as jest.Mock).mockReturnValue(true);
    (GetCurrentUser as jest.Mock).mockReturnValue(MakeAdminUser());
}

/** Arm the auth mocks so the current principal is a regular (non-admin) user. */
function ArmRegularSession(): void {
    (IsAdmin as jest.Mock).mockReturnValue(false);
    (GetCurrentUser as jest.Mock).mockReturnValue(MakeCurrentUser());
}

/** Render the real UsersPage inside the shared MUI theme provider. */
function RenderUsersPage() {
    return RenderWithProviders(<UsersPage />);
}

describe('UsersPage', () => {
    beforeEach(() => {
        // `clearMocks: true` clears call data before each test but not queued
        // implementations; reset the auth + UsersApi mocks so every arm starts
        // from a clean, deterministic slate.
        (IsAdmin as jest.Mock).mockReset();
        (GetCurrentUser as jest.Mock).mockReset();
        (UsersApi.ListUsers as jest.Mock).mockReset();
        (UsersApi.AddUser as jest.Mock).mockReset();
        (UsersApi.GetUser as jest.Mock).mockReset();
        (UsersApi.UpdateUser as jest.Mock).mockReset();
        (UsersApi.DeleteUser as jest.Mock).mockReset();
    });

    // -----------------------------------------------------------------------
    // Admin access boundary (asserted on BOTH roles + the server 403 branch).
    // -----------------------------------------------------------------------
    describe('admin access', () => {
        it('renders the user list for an admin without redirecting to /menu', async () => {
            ArmAdminSession();
            const regularUser: UserSummary = MakeUserSummary();
            const adminUser: UserSummary = MakeUserSummary({
                user_id: ADMIN_USER_ID,
                user_type: 'A',
            });
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([regularUser, adminUser]),
            );

            RenderUsersPage();

            // The list is fetched with only the real {page, page_size} contract.
            await waitFor(() =>
                expect(UsersApi.ListUsers).toHaveBeenCalledWith({
                    page: 1,
                    page_size: DEFAULT_PAGE_SIZE,
                }),
            );

            expect(await screen.findByText(REGULAR_USER_ID)).toBeInTheDocument();
            expect(screen.getByText(ADMIN_USER_ID)).toBeInTheDocument();
            // The Type column render maps user_type 'A' -> 'Admin'.
            expect(screen.getByText('Admin')).toBeInTheDocument();
            expect(
                screen.getByRole('heading', { name: 'List Users' }),
            ).toBeInTheDocument();
            // Admin gate passed and the load succeeded: no redirect occurred.
            expect(mockReplace).not.toHaveBeenCalledWith(MENU_ROUTE);
            // Security: UserSummary has no password; none may surface in the UI.
            expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
        });

        it('renders every COUSR00 column header', async () => {
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([MakeUserSummary()]),
            );

            RenderUsersPage();

            await screen.findByText(REGULAR_USER_ID);
            for (const header of COLUMN_HEADERS) {
                expect(
                    screen.getByRole('columnheader', { name: header }),
                ).toBeInTheDocument();
            }
        });

        it('redirects a non-admin to /menu and renders no table', async () => {
            ArmRegularSession();

            RenderUsersPage();

            await waitFor(() =>
                expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE),
            );
            // The admin-only page renders nothing meaningful for a regular user.
            expect(screen.queryByRole('table')).not.toBeInTheDocument();
            expect(
                screen.queryByRole('heading', { name: 'List Users' }),
            ).not.toBeInTheDocument();
            // A non-admin must never reach the list endpoint.
            expect(UsersApi.ListUsers).not.toHaveBeenCalled();
        });

        it('redirects to /menu when the server rejects ListUsers with 403', async () => {
            // Client gate passes (admin) but the server require_admin boundary 403s.
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockRejectedValueOnce(
                new ApiError({ status: FORBIDDEN_STATUS, message: 'Forbidden' }),
            );

            RenderUsersPage();

            await waitFor(() =>
                expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE),
            );
        });
    });

    // -----------------------------------------------------------------------
    // Search field contract (length bound + client-side filter).
    // -----------------------------------------------------------------------
    describe('search field', () => {
        it('bounds the search input to 8 characters and filters client-side', async () => {
            const user = SetupUser();
            ArmAdminSession();
            const regularUser: UserSummary = MakeUserSummary();
            const adminUser: UserSummary = MakeUserSummary({
                user_id: ADMIN_USER_ID,
                user_type: 'A',
            });
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([regularUser, adminUser]),
            );

            RenderUsersPage();

            expect(await screen.findByText(REGULAR_USER_ID)).toBeInTheDocument();
            expect(screen.getByText(ADMIN_USER_ID)).toBeInTheDocument();
            // The real ListUsers contract carries only {page, page_size} — search
            // is a client-side filter, never a server parameter.
            expect(UsersApi.ListUsers).toHaveBeenCalledWith({
                page: 1,
                page_size: DEFAULT_PAGE_SIZE,
            });

            const searchInput = screen.getByLabelText(SEARCH_LABEL);
            expect(searchInput).toHaveAttribute(
                'maxlength',
                String(SEARCH_MAX_LENGTH),
            );

            // Typing an admin-id prefix filters the current page down client-side.
            await user.type(searchInput, 'ADMIN');

            await waitFor(() =>
                expect(
                    screen.queryByText(REGULAR_USER_ID),
                ).not.toBeInTheDocument(),
            );
            expect(screen.getByText(ADMIN_USER_ID)).toBeInTheDocument();
            // The filter is client-side: no additional server fetch was issued.
            expect(UsersApi.ListUsers).toHaveBeenCalledTimes(1);
        });
    });

    // -----------------------------------------------------------------------
    // Row + page navigation (encoded routes, Add, pagination refetch).
    // -----------------------------------------------------------------------
    describe('navigation', () => {
        it('navigates to the encoded update route for a row', async () => {
            const user = SetupUser();
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([MakeUserSummary()]),
            );

            RenderUsersPage();

            await screen.findByText(REGULAR_USER_ID);
            const userRow = screen.getByRole('row', {
                name: new RegExp(REGULAR_USER_ID),
            });
            await user.click(
                within(userRow).getByRole('button', { name: /^Update user/ }),
            );

            expect(mockPush).toHaveBeenCalledWith(UPDATE_ROUTE);
        });

        it('navigates to the encoded delete route for a row', async () => {
            const user = SetupUser();
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([MakeUserSummary()]),
            );

            RenderUsersPage();

            await screen.findByText(REGULAR_USER_ID);
            const userRow = screen.getByRole('row', {
                name: new RegExp(REGULAR_USER_ID),
            });
            await user.click(
                within(userRow).getByRole('button', { name: /^Delete user/ }),
            );

            expect(mockPush).toHaveBeenCalledWith(DELETE_ROUTE);
        });

        it('navigates to /users/add from the Add User action', async () => {
            const user = SetupUser();
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([MakeUserSummary()]),
            );

            RenderUsersPage();

            await screen.findByText(REGULAR_USER_ID);
            await user.click(screen.getByRole('button', { name: 'Add User' }));

            expect(mockPush).toHaveBeenCalledWith(ADD_USER_ROUTE);
        });

        it('refetches the next page when pagination changes', async () => {
            const user = SetupUser();
            ArmAdminSession();
            const firstPage: PaginatedResponse<UserSummary> =
                MakePaginatedResponse([MakeUserSummary()], {
                    page: 1,
                    total_items: 8,
                    total_pages: 2,
                    has_next: true,
                });
            const secondPage: PaginatedResponse<UserSummary> =
                MakePaginatedResponse([MakeUserSummary({ user_id: 'USER0002' })], {
                    page: 2,
                    total_items: 8,
                    total_pages: 2,
                    has_previous: true,
                });
            (UsersApi.ListUsers as jest.Mock)
                .mockResolvedValueOnce(firstPage)
                .mockResolvedValueOnce(secondPage);

            RenderUsersPage();

            await screen.findByText(REGULAR_USER_ID);
            await user.click(
                screen.getByRole('button', { name: /go to page 2/i }),
            );

            await waitFor(() =>
                expect(UsersApi.ListUsers).toHaveBeenLastCalledWith({
                    page: 2,
                    page_size: DEFAULT_PAGE_SIZE,
                }),
            );
            expect(UsersApi.ListUsers).toHaveBeenCalledTimes(2);
        });
    });

    // -----------------------------------------------------------------------
    // Empty + error states.
    // -----------------------------------------------------------------------
    describe('empty and error states', () => {
        it('shows the empty message when there are no users', async () => {
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockResolvedValueOnce(
                MakePaginatedResponse([], { total_items: 0, total_pages: 0 }),
            );

            RenderUsersPage();

            // The page overrides DataTable's default ('No records found.') with a
            // users-specific empty message.
            expect(
                await screen.findByText('No users found.'),
            ).toBeInTheDocument();
        });

        it('shows an error alert and does not redirect on a non-403 API error', async () => {
            ArmAdminSession();
            (UsersApi.ListUsers as jest.Mock).mockRejectedValueOnce(
                new ApiError({
                    status: SERVER_ERROR_STATUS,
                    message: 'Internal Server Error',
                }),
            );

            RenderUsersPage();

            const alert = await screen.findByRole('alert');
            expect(alert).toHaveTextContent('Internal Server Error');
            // A non-403 failure surfaces an alert but never redirects to /menu.
            expect(mockReplace).not.toHaveBeenCalledWith(MENU_ROUTE);
        });
    });

    describe('stale response handling (M-05)', () => {
        it('discards a superseded page fetch so a slow earlier page cannot overwrite a newer one', async () => {
            const user = SetupUser();
            ArmAdminSession();

            const STALE_USER_ID = 'STALE002';
            const FRESH_USER_ID = 'FRESH001';

            // Mount (page 1) resolves immediately with a 3-page envelope so the
            // pagination control renders; the page-2 and page-3 fetches are
            // hand-controlled (deferred) so the spec dictates resolution ORDER.
            let resolveStalePageTwo!: (
                value: PaginatedResponse<UserSummary>,
            ) => void;
            let resolveFreshPageThree!: (
                value: PaginatedResponse<UserSummary>,
            ) => void;
            const stalePageTwo = new Promise<PaginatedResponse<UserSummary>>(
                (resolve) => {
                    resolveStalePageTwo = resolve;
                },
            );
            const freshPageThree = new Promise<PaginatedResponse<UserSummary>>(
                (resolve) => {
                    resolveFreshPageThree = resolve;
                },
            );

            const listMock = UsersApi.ListUsers as jest.Mock;
            listMock
                .mockResolvedValueOnce(
                    MakePaginatedResponse<UserSummary>(
                        [MakeUserSummary({ user_id: REGULAR_USER_ID })],
                        { page: 1, total_items: 21, total_pages: 3, has_next: true },
                    ),
                ) // mount -> generation 1 (page 1)
                .mockReturnValueOnce(stalePageTwo) // page 2 -> older generation
                .mockReturnValueOnce(freshPageThree); // page 3 -> newer generation

            RenderUsersPage();
            await screen.findByText(REGULAR_USER_ID);

            // Advance to page 2 (older request, left in flight), then to page 3
            // (newer request). The grid shows page 1 until a fetch resolves, so
            // both page buttons stay clickable.
            await user.click(
                screen.getByRole('button', { name: /go to page 2/i }),
            );
            await user.click(
                screen.getByRole('button', { name: /go to page 3/i }),
            );
            await waitFor(() => expect(listMock).toHaveBeenCalledTimes(3));

            // Resolve the NEWER (page 3) request first; its row must render.
            resolveFreshPageThree(
                MakePaginatedResponse<UserSummary>(
                    [MakeUserSummary({ user_id: FRESH_USER_ID })],
                    { page: 3, total_items: 21, total_pages: 3, has_previous: true },
                ),
            );
            expect(await screen.findByText(FRESH_USER_ID)).toBeInTheDocument();

            // Now resolve the OLDER (page 2) request. The generation guard must
            // discard it: the page-3 row stays and the page-2 row never appears.
            resolveStalePageTwo(
                MakePaginatedResponse<UserSummary>(
                    [MakeUserSummary({ user_id: STALE_USER_ID })],
                    {
                        page: 2,
                        total_items: 21,
                        total_pages: 3,
                        has_next: true,
                        has_previous: true,
                    },
                ),
            );
            await waitFor(() =>
                expect(screen.getByText(FRESH_USER_ID)).toBeInTheDocument(),
            );
            expect(screen.queryByText(STALE_USER_ID)).not.toBeInTheDocument();
        });
    });
});
