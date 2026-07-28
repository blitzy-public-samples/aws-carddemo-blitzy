/**
 * Delete User page spec — legacy origin BMS COUSR03 / Tx CU03 /
 * program COUSR03C. Admin-only; confirm-before-delete.
 */

/*
 * Component / integration test for the modern Delete User page
 * (`@/app/users/delete/page`, default export `UsersDeletePage`), the replacement
 * for the legacy 3270 "Delete User" screen (app/bms/COUSR03.bms, mapset COUSR3A,
 * app/cpy-bms/COUSR03.CPY; CICS transaction CU03; COBOL program COUSR03C).
 *
 * The real page is rendered with three collaborators mocked so no real network,
 * navigation, or persisted identity is exercised:
 *   - `next/navigation` — controlled `useRouter` / `useSearchParams` (the deep
 *     link `?userId=<id>` drives the auto-fetch) / `usePathname`.
 *   - `@/lib/auth`      — `IsAdmin` / `GetCurrentUser` are armed per test to
 *     exercise both the admin and the non-admin (redirect) paths.
 *   - `@/lib/apiClient` — only `UsersApi` is mocked; `ApiError` and `IsApiError`
 *     are kept REAL so the page's 403 / 404 branching runs against the genuine
 *     typed-error helpers.
 *
 * SECURITY (Ochs Test Rule): COUSR03 carries no password and `UserRead` exposes
 * none, so the specs assert that nothing labeled password / secret ever renders.
 */

import UsersDeletePage from '@/app/users/delete/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    fireEvent,
    SetupUser,
    MakeAdminUser,
    MakeCurrentUser,
} from '../testUtils';
import { UsersApi, ApiError, IsApiError } from '@/lib/apiClient';
import { IsAdmin, GetCurrentUser } from '@/lib/auth';
import type { UserRead } from '@/types';

/* ------------------------------------------------------------------------- */
/* next/navigation mock — module-scope, `mock`-prefixed so the hoisted factory */
/* may close over them (Jest/SWC hoisting rule). Reset in `beforeEach`.        */
/* ------------------------------------------------------------------------- */

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockRefresh = jest.fn();
let mockSearchParams = new URLSearchParams();

jest.mock('next/navigation', () => ({
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        refresh: mockRefresh,
        back: jest.fn(),
        forward: jest.fn(),
        prefetch: jest.fn(),
    }),
    usePathname: () => '/users/delete',
    useSearchParams: () => mockSearchParams,
}));

/* Automock the identity helpers; each test arms `IsAdmin` / `GetCurrentUser`. */
jest.mock('@/lib/auth');

/*
 * Mock only `UsersApi`; keep the rest of the client REAL. Preserving the genuine
 * `ApiError` class and `IsApiError` guard is required so the page's 403 -> menu
 * redirect and 404 -> not-found branches behave exactly as in production.
 */
jest.mock('@/lib/apiClient', () => {
    const actualApiClient =
        jest.requireActual<typeof import('@/lib/apiClient')>('@/lib/apiClient');
    return {
        ...actualApiClient,
        UsersApi: {
            ListUsers: jest.fn(),
            AddUser: jest.fn(),
            GetUser: jest.fn(),
            UpdateUser: jest.fn(),
            DeleteUser: jest.fn(),
        },
    };
});

/* ------------------------------------------------------------------------- */
/* Constants (Ochs rule: ALL_UPPERCASE with underscores). Message / label text */
/* is taken verbatim from the real users/delete/page.tsx.                      */
/* ------------------------------------------------------------------------- */

/** README seed user id used across the deep-link / fetch / delete scenarios. */
const SAMPLE_USER_ID = 'USER0001';

/** Route a non-admin (or a 403) is redirected to. */
const MENU_ROUTE = '/menu';

/** Admin user-list route — the Back-button target (NOT auto-navigated on delete). */
const USERS_LIST_ROUTE = '/users';

/** Page heading and ConfirmDialog title (they share this text). */
const PAGE_TITLE = 'Delete User';

/** Blank-User-ID guard message (page `MSG_EMPTY_USER_ID`). */
const MSG_EMPTY_USER_ID = 'User ID can NOT be empty...';

/** Confirm hint shown once a user is loaded (page `MSG_CONFIRM_HINT`). */
// QA M1: hint copy aligned to the on-page "Back" button (was "Cancel").
const MSG_CONFIRM_HINT = 'Press Delete to remove this user, or Back to abort.';

/** Not-found message on a 404 fetch (page `MSG_USER_NOT_FOUND`). */
const MSG_USER_NOT_FOUND = 'User ID NOT found...';

/** Composed delete-success message: `User <id> has been deleted ...`. */
const MSG_DELETE_SUCCESS = `User ${SAMPLE_USER_ID} has been deleted ...`;

/** HTTP status the page branches on. */
const HTTP_FORBIDDEN = 403;
const HTTP_NOT_FOUND = 404;

/* ------------------------------------------------------------------------- */
/* Typed handles to the mocked collaborators (Jest 29 `jest.mocked`).          */
/* ------------------------------------------------------------------------- */

const getUserMock = jest.mocked(UsersApi.GetUser);
const deleteUserMock = jest.mocked(UsersApi.DeleteUser);
const isAdminMock = jest.mocked(IsAdmin);
const getCurrentUserMock = jest.mocked(GetCurrentUser);

/* ------------------------------------------------------------------------- */
/* Test helpers (Ochs: PascalCase functions, single object param, tiny).       */
/* ------------------------------------------------------------------------- */

/**
 * Build a `UserRead` fixture. Defaults to the regular seed user (`user_type:'U'`
 * -> displayed as 'User'); pass overrides for admin or alternate names. There is
 * intentionally no password field — reads never carry credentials.
 *
 * @param overrides - Partial fields that replace any default.
 * @returns A valid `UserRead`.
 */
function MakeFetchedUser(overrides?: Partial<UserRead>): UserRead {
    const baseUser: UserRead = {
        user_id: SAMPLE_USER_ID,
        first_name: 'Test',
        last_name: 'User',
        user_type: 'U',
    };

    return { ...baseUser, ...overrides };
}

/**
 * Construct a genuine `ApiError` (the real, unmocked class) carrying `status`,
 * so the page's `IsApiError(err) && err.status === ...` branches run for real.
 *
 * @param status - The HTTP status to embed (for example 403 or 404).
 * @returns A real `ApiError` instance.
 */
function MakeApiError(status: number): ApiError {
    return new ApiError({ status, message: `HTTP ${status} error` });
}

/** Arms the identity helpers so the page treats the caller as an administrator. */
function ArmAdmin(): void {
    isAdminMock.mockReturnValue(true);
    getCurrentUserMock.mockReturnValue(MakeAdminUser());
}

/** Arms the identity helpers so the page treats the caller as a regular user. */
function ArmNonAdmin(): void {
    isAdminMock.mockReturnValue(false);
    getCurrentUserMock.mockReturnValue(MakeCurrentUser());
}

/** Renders the real page inside the application MUI theme. */
function RenderPage(): void {
    RenderWithProviders(<UsersDeletePage />);
}

/* ------------------------------------------------------------------------- */
/* Suite.                                                                     */
/* ------------------------------------------------------------------------- */

describe('UsersDeletePage', () => {
    beforeEach(() => {
        // Fresh, empty search params each test (a spec that deep-links sets its
        // own). `clearMocks` only clears CALL data, so reset implementations and
        // any queued once-values here to prevent cross-test leakage.
        mockSearchParams = new URLSearchParams();
        mockPush.mockReset();
        mockReplace.mockReset();
        mockRefresh.mockReset();
        getUserMock.mockReset();
        deleteUserMock.mockReset();
        isAdminMock.mockReset();
        getCurrentUserMock.mockReset();
    });

    // ----------------------------------------------------------------------
    // 1. Non-admin redirect (admin-only screen).
    // ----------------------------------------------------------------------

    it('redirects a non-admin to the menu and renders no delete form', async () => {
        ArmNonAdmin();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });

        RenderPage();

        await waitFor(() => {
            expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE);
        });
        expect(
            screen.queryByRole('heading', { name: PAGE_TITLE }),
        ).not.toBeInTheDocument();
        // The admin gate short-circuits before the deep-link fetch effect runs.
        expect(getUserMock).not.toHaveBeenCalled();
    });

    it('titles the page with the h5 typography scale shared across user-CRUD screens (QA I22c)', () => {
        ArmAdmin();

        RenderPage();

        // The page title is the single semantic level-1 heading...
        const heading = screen.getByRole('heading', {
            level: 1,
            name: PAGE_TITLE,
        });

        // ...rendered at the MUI `h5` typography scale, matching the other
        // user-CRUD screens (Users list / Add / Update). Before the QA I22c
        // fix this page used the larger `h4` scale, making the admin User
        // section visually inconsistent. Assert the VISUAL variant (the
        // semantic level intentionally stays h1).
        expect(heading.className).toMatch(/MuiTypography-h5/);
        expect(heading.className).not.toMatch(/MuiTypography-h4/);
    });

    // ----------------------------------------------------------------------
    // 2. Deep-link auto-fetch (admin) -> read-only details + confirm hint.
    // ----------------------------------------------------------------------

    it('auto-fetches the deep-linked user and shows read-only details', async () => {
        ArmAdmin();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        getUserMock.mockResolvedValueOnce(
            MakeFetchedUser({
                first_name: 'Test',
                last_name: 'User',
                user_type: 'U',
            }),
        );

        RenderPage();

        await waitFor(() => {
            expect(getUserMock).toHaveBeenCalledWith(SAMPLE_USER_ID);
        });

        expect(await screen.findByText('Test')).toBeInTheDocument();
        expect(screen.getByText('First Name')).toBeInTheDocument();
        expect(screen.getByText('Last Name')).toBeInTheDocument();
        expect(screen.getByText('User Type')).toBeInTheDocument();
        // user_type 'U' renders as 'User' (never 'Admin').
        expect(screen.getAllByText('User').length).toBeGreaterThanOrEqual(1);
        expect(screen.queryByText('Admin')).not.toBeInTheDocument();
        // Confirm hint appears once a user is loaded.
        expect(screen.getByText(MSG_CONFIRM_HINT)).toBeInTheDocument();
        // No credential is ever exposed on a read.
        expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // 3. Empty id fetch -> guard message, API untouched.
    // ----------------------------------------------------------------------

    it('rejects an empty User ID on fetch without calling the API', async () => {
        ArmAdmin();
        const user = SetupUser();

        RenderPage();

        const fetchButton = await screen.findByRole('button', { name: 'Fetch' });
        await user.click(fetchButton);

        expect(await screen.findByText(MSG_EMPTY_USER_ID)).toBeInTheDocument();
        expect(getUserMock).not.toHaveBeenCalled();
    });

    // ----------------------------------------------------------------------
    // 4. Open ConfirmDialog, confirm -> DeleteUser (204, no body).
    // ----------------------------------------------------------------------

    it('deletes only after the ConfirmDialog is confirmed (204, no body read)', async () => {
        ArmAdmin();
        const user = SetupUser();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        getUserMock.mockResolvedValueOnce(MakeFetchedUser());

        RenderPage();

        // Deep-link fetch resolves and the read-only details render.
        await waitFor(() => {
            expect(getUserMock).toHaveBeenCalledWith(SAMPLE_USER_ID);
        });
        await screen.findByText('Test');

        // Open the confirmation dialog via the page's Delete button (dialog is
        // still closed here, so there is exactly one 'Delete' button).
        await user.click(screen.getByRole('button', { name: 'Delete' }));

        const dialog = await screen.findByRole('dialog');
        expect(within(dialog).getByText(PAGE_TITLE)).toBeInTheDocument();
        const confirmButton = within(dialog).getByRole('button', {
            name: 'Delete',
        });
        // ConfirmDialog gate: destructive confirm color.
        expect(confirmButton.className).toMatch(/colorError|containedError/i);
        // Nothing deleted yet — confirmation is required first.
        expect(deleteUserMock).not.toHaveBeenCalled();

        // DeleteUser returns 204 with no body -> the mock resolves `undefined`
        // and the page must NOT read `.data` from it.
        deleteUserMock.mockResolvedValueOnce(undefined);
        await user.click(confirmButton);

        await waitFor(() => {
            expect(deleteUserMock).toHaveBeenCalledWith(SAMPLE_USER_ID);
        });
        expect(await screen.findByText(MSG_DELETE_SUCCESS)).toBeInTheDocument();
        // fetchedUser is cleared after a successful delete.
        await waitFor(() => {
            expect(screen.queryByText('Test')).not.toBeInTheDocument();
        });
        // FINDING-04: the page STAYS on the Delete User screen so the green
        // success message is actually seen — it must NOT auto-navigate to the
        // admin user list (that instantaneous redirect is exactly why the old
        // success message never painted) and must not silently refresh.
        expect(mockPush).not.toHaveBeenCalledWith(USERS_LIST_ROUTE);
        expect(mockRefresh).not.toHaveBeenCalled();
    });

    // ----------------------------------------------------------------------
    // 5. Cancel dialog -> no delete, dialog closes.
    // ----------------------------------------------------------------------

    it('closes the dialog on Cancel without deleting', async () => {
        ArmAdmin();
        const user = SetupUser();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        getUserMock.mockResolvedValueOnce(MakeFetchedUser());

        RenderPage();

        await screen.findByText('Test');
        await user.click(screen.getByRole('button', { name: 'Delete' }));

        const dialog = await screen.findByRole('dialog');
        await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));

        expect(deleteUserMock).not.toHaveBeenCalled();
        await waitFor(() => {
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        });
    });

    // ----------------------------------------------------------------------
    // 6. 404 on fetch -> not-found message, no details.
    // ----------------------------------------------------------------------

    it('shows the not-found message on a 404 fetch and renders no details', async () => {
        ArmAdmin();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        const notFoundError = MakeApiError(HTTP_NOT_FOUND);
        getUserMock.mockRejectedValueOnce(notFoundError);

        RenderPage();

        await waitFor(() => {
            expect(getUserMock).toHaveBeenCalledWith(SAMPLE_USER_ID);
        });
        expect(await screen.findByText(MSG_USER_NOT_FOUND)).toBeInTheDocument();
        expect(screen.queryByText('First Name')).not.toBeInTheDocument();
        // Sanity: the REAL IsApiError recognizes the REAL ApiError instance.
        expect(IsApiError(notFoundError)).toBe(true);
    });

    // ----------------------------------------------------------------------
    // 7. 403 on fetch -> redirect to the menu (server require_admin boundary).
    // ----------------------------------------------------------------------

    it('redirects to the menu when the fetch is forbidden (403)', async () => {
        ArmAdmin();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        getUserMock.mockRejectedValueOnce(MakeApiError(HTTP_FORBIDDEN));

        RenderPage();

        await waitFor(() => {
            expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE);
        });
    });

    // ----------------------------------------------------------------------
    // 8. Suspense boundary resolves to content without crashing.
    // ----------------------------------------------------------------------

    it('renders through the Suspense boundary without crashing', async () => {
        ArmAdmin();

        RenderPage();

        expect(
            await screen.findByRole('heading', { name: PAGE_TITLE }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: 'Fetch' }),
        ).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // 9. No password / secret ever rendered (fetched + confirm states).
    // ----------------------------------------------------------------------

    it('never renders a password or secret across fetch and confirm states', async () => {
        ArmAdmin();
        const user = SetupUser();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        getUserMock.mockResolvedValueOnce(MakeFetchedUser());

        RenderPage();

        await screen.findByText('Test');
        expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
        expect(screen.queryByText(/secret/i)).not.toBeInTheDocument();
        expect(document.querySelector('input[type="password"]')).toBeNull();

        // Re-check in the confirm state.
        await user.click(screen.getByRole('button', { name: 'Delete' }));
        await screen.findByRole('dialog');
        expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
        expect(screen.queryByText(/secret/i)).not.toBeInTheDocument();
        expect(document.querySelector('input[type="password"]')).toBeNull();
    });

    // ----------------------------------------------------------------------
    // 10. Keyboard-shortcut scoping (QA M-28) — the window-level Enter shortcut
    // must STAND DOWN when a focused interactive control already owns Enter, so
    // it never duplicates the fetch.
    // ----------------------------------------------------------------------

    it('does not re-fetch when Enter fires on a focused button (M-28)', async () => {
        ArmAdmin();
        mockSearchParams = new URLSearchParams({ userId: SAMPLE_USER_ID });
        getUserMock.mockResolvedValue(MakeFetchedUser());

        RenderPage();

        // The deep-link auto-fetch runs exactly once.
        await waitFor(() => {
            expect(getUserMock).toHaveBeenCalledTimes(1);
        });

        // Enter pressed while the "Back" button is focused: the window shortcut
        // reads event.target (the button), recognizes it as an interactive
        // control that owns Enter, and stands down — so NO second fetch fires.
        const backButton = screen.getByRole('button', { name: 'Back' });
        fireEvent.keyDown(backButton, { key: 'Enter' });

        expect(getUserMock).toHaveBeenCalledTimes(1);
    });

    it('still fetches when Enter fires outside an interactive control (M-28)', async () => {
        ArmAdmin();
        const user = SetupUser();
        getUserMock.mockResolvedValue(MakeFetchedUser());

        RenderPage();

        // Type an id into the free-text field and press Enter on that input.
        // A text input does NOT own the screen Enter shortcut, so the legacy
        // "type an id, press Enter to load" behavior is preserved. Typing via
        // user-event flushes React updates so the window listener closes over
        // the latest id before Enter is dispatched.
        const idInput = await screen.findByLabelText(/user id/i);
        await user.type(idInput, `${SAMPLE_USER_ID}{Enter}`);

        await waitFor(() => {
            expect(getUserMock).toHaveBeenCalledWith(SAMPLE_USER_ID);
        });
    });
});
