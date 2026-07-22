/** Update User page spec — legacy origin BMS COUSR02 / Tx CU02 / program COUSR02C. Admin-only; password optional (blank=keep). */

/*
 * WHAT IS VERIFIED (the real @/app/users/update/page is rendered; NO real
 * network — @/lib/apiClient's UsersApi and @/lib/auth are jest doubles):
 *   - the admin gate redirects a non-admin to /menu and renders no form;
 *   - the ?userId= deep link auto-fetches the record EXACTLY ONCE and populates
 *     the editable fields, leaving the (never-echoed) password blank;
 *   - a manual Fetch with an empty id surfaces a friendly validation message and
 *     calls no API;
 *   - CRITICAL invariant: the UpdateUser body OMITS `password` when the field is
 *     left blank, INCLUDES it when the admin types one, and NEVER carries
 *     `user_id` (the id travels as the PUT path parameter);
 *   - a 404 fetch shows the friendly "User not found" message and stays unfetched;
 *   - a 403 (fetch) redirects to /menu; Save & Exit (F3) returns to /users.
 *
 * MOCKING NOTES (do not "simplify" away):
 *   1. next/navigation is replaced with a controlled router + resettable
 *      `mockSearchParams`. Every closed-over identifier is `mock`-prefixed so the
 *      hoisted jest.mock factory may legally reference it (see the SWC hoist rule
 *      documented in __tests__/lib/apiClient.test.ts).
 *   2. @/lib/apiClient keeps ApiError / IsApiError REAL (the page and ErrorAlert
 *      narrow 403/404 via `instanceof ApiError`); only `UsersApi` is a double.
 */

/* --------------------------------------------------------------------------- */
/* Controlled next/navigation router + search params (mock-prefixed).          */
/* --------------------------------------------------------------------------- */

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();
let mockSearchParams = new URLSearchParams();

jest.mock('next/navigation', () => ({
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
    }),
    useSearchParams: () => mockSearchParams,
    usePathname: () => '/users/update',
}));

/* Partial mock: keep the whole real module (ApiError / IsApiError) and swap only
   UsersApi for controllable jest fns. requireActual is allowed inside a factory. */
jest.mock('@/lib/apiClient', () => {
    const actual = jest.requireActual('@/lib/apiClient');
    return {
        __esModule: true,
        ...actual,
        UsersApi: {
            ListUsers: jest.fn(),
            AddUser: jest.fn(),
            GetUser: jest.fn(),
            UpdateUser: jest.fn(),
            DeleteUser: jest.fn(),
        },
    };
});

/* Auto-mock the client-side auth helpers; IsAdmin / GetCurrentUser armed per test. */
jest.mock('@/lib/auth');

import UsersUpdatePage from '@/app/users/update/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    SetupUser,
    MakeAdminUser,
    MakeCurrentUser,
} from '../testUtils';
import { UsersApi, ApiError, IsApiError } from '@/lib/apiClient';
import { IsAdmin, GetCurrentUser } from '@/lib/auth';
import type { UserRead, UserUpdate } from '@/types';

/* --------------------------------------------------------------------------- */
/* Typed handles to the mocked collaborators. `clearMocks: true` wipes their    */
/* call history before every test but never the fn identity, so these constants */
/* stay valid for the whole suite.                                              */
/* --------------------------------------------------------------------------- */

const mockGetUser = jest.mocked(UsersApi.GetUser);
const mockUpdateUser = jest.mocked(UsersApi.UpdateUser);
const mockIsAdmin = jest.mocked(IsAdmin);
const mockGetCurrentUser = jest.mocked(GetCurrentUser);

/* --------------------------------------------------------------------------- */
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores).                 */
/* --------------------------------------------------------------------------- */

/** Deep-link / lookup user id used across scenarios (README seed USER0001). */
const TARGET_USER_ID = 'USER0001';

/** Routes the page navigates to (mirror the page's own MENU_ROUTE/USERS_ROUTE). */
const MENU_ROUTE = '/menu';
const USERS_ROUTE = '/users';

/** Named HTTP status codes handled by the page's catch blocks (no magic numbers). */
const HTTP_FORBIDDEN = 403;
const HTTP_NOT_FOUND = 404;

/**
 * Throwaway 8-character password (PASSWD PIC X(8)) typed ONLY to prove it is
 * forwarded to UpdateUser. It is an obviously-fake test value, never a real
 * credential, and is never persisted or echoed.
 */
const TYPED_PASSWORD = 'Np1Test2';

/** New first-name value typed to prove the edited field reaches the payload. */
const UPDATED_FIRST_NAME = 'Updated';

/* --------------------------------------------------------------------------- */
/* Test helpers (Ochs rule: PascalCase functions).                             */
/* --------------------------------------------------------------------------- */

/**
 * Build the {@link UserRead} that a successful GetUser resolves with. Mirrors the
 * backend contract exactly: there is deliberately NO password field on a read.
 *
 * @param overrides - Partial fields that replace any default.
 * @returns A valid `UserRead` fixture.
 */
function MakeUserRead(overrides?: Partial<UserRead>): UserRead {
    const baseUser: UserRead = {
        user_id: TARGET_USER_ID,
        first_name: 'Test',
        last_name: 'User',
        user_type: 'U',
    };

    return { ...baseUser, ...overrides };
}

/**
 * Arm the admin gate as an administrator — the precondition for every scenario
 * that reaches the fetch/edit form. IsAdmin drives the client-side gate; the
 * mirrored CurrentUser is armed too for fidelity (the page reads IsAdmin only).
 */
function ArmAdminUser(): void {
    mockIsAdmin.mockReturnValue(true);
    mockGetCurrentUser.mockReturnValue(MakeAdminUser());
}

/* Reset the deep-link search params before each test; individual tests assign a
   `?userId=` value prior to rendering when they need the auto-fetch path. */
beforeEach(() => {
    mockSearchParams = new URLSearchParams();
});

describe('UsersUpdatePage', () => {
    describe('admin gate', () => {
        it('redirects a non-admin to /menu and renders no form', async () => {
            mockIsAdmin.mockReturnValue(false);
            mockGetCurrentUser.mockReturnValue(MakeCurrentUser());
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });

            RenderWithProviders(<UsersUpdatePage />);

            await waitFor(() =>
                expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE),
            );
            // The gate renders null for non-admins — no title, no form controls.
            expect(
                screen.queryByRole('heading', { name: 'Update User' }),
            ).toBeNull();
            expect(
                screen.queryByRole('button', { name: 'Fetch' }),
            ).toBeNull();
            // A non-admin never reaches the data layer.
            expect(mockGetUser).not.toHaveBeenCalled();
        });
    });

    describe('deep-link auto-fetch', () => {
        it('auto-fetches the ?userId= record and populates the editable fields with a blank password', async () => {
            ArmAdminUser();
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockResolvedValueOnce(
                MakeUserRead({
                    first_name: 'Test',
                    last_name: 'User',
                    user_type: 'U',
                }),
            );

            RenderWithProviders(<UsersUpdatePage />);

            await waitFor(() =>
                expect(mockGetUser).toHaveBeenCalledWith(TARGET_USER_ID),
            );

            // Editable fields populate from the fetched UserRead.
            const firstNameField = await screen.findByLabelText(/First Name/i);
            expect(firstNameField).toHaveValue('Test');
            expect(screen.getByLabelText(/Last Name/i)).toHaveValue('User');
            // The user-type Select reflects 'U' -> its label 'User'.
            expect(
                screen.getByRole('combobox', { name: /User Type/i }),
            ).toHaveTextContent('User');

            // The password is NEVER populated from a read (UserRead carries none).
            const passwordField = screen.getByLabelText(/Password/i);
            expect(passwordField).toHaveAttribute('type', 'password');
            expect(passwordField).toHaveValue('');

            // The lookup key is locked (readOnly) and shows the fetched id.
            const userIdField = screen.getByLabelText(/User ID/i);
            expect(userIdField).toHaveValue(TARGET_USER_ID);
            expect(userIdField).toHaveAttribute('readonly');
        });

        it('auto-fetches only once and the one-shot guard survives a re-render', async () => {
            const user = SetupUser();
            ArmAdminUser();
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockResolvedValueOnce(MakeUserRead());

            RenderWithProviders(<UsersUpdatePage />);

            const firstNameField = await screen.findByLabelText(/First Name/i);
            expect(mockGetUser).toHaveBeenCalledTimes(1);

            // Editing a field forces a re-render; the guard must not re-fetch.
            await user.type(firstNameField, 'Z');
            expect(mockGetUser).toHaveBeenCalledTimes(1);
        });
    });

    describe('manual fetch validation', () => {
        it('shows a validation message and calls no API when fetching with an empty id', async () => {
            const user = SetupUser();
            ArmAdminUser();
            // No ?userId= (beforeEach reset) -> no auto-fetch; id field stays empty.

            RenderWithProviders(<UsersUpdatePage />);

            const fetchButton = await screen.findByRole('button', {
                name: 'Fetch',
            });
            await user.click(fetchButton);

            expect(
                await screen.findByText(/User ID can NOT be empty/i),
            ).toBeInTheDocument();
            expect(mockGetUser).not.toHaveBeenCalled();
        });
    });

    describe('save payload (password / user_id invariants)', () => {
        it('OMITS the password and never sends user_id when saving with a blank password', async () => {
            const user = SetupUser();
            ArmAdminUser();
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockResolvedValueOnce(MakeUserRead());
            mockUpdateUser.mockResolvedValueOnce(
                MakeUserRead({ first_name: UPDATED_FIRST_NAME }),
            );

            RenderWithProviders(<UsersUpdatePage />);

            const firstNameField = await screen.findByLabelText(/First Name/i);
            // Leave the password blank; edit only the first name.
            await user.clear(firstNameField);
            await user.type(firstNameField, UPDATED_FIRST_NAME);
            await user.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() =>
                expect(mockUpdateUser).toHaveBeenCalledWith(
                    TARGET_USER_ID,
                    expect.objectContaining({
                        first_name: expect.any(String),
                        last_name: expect.any(String),
                        user_type: expect.any(String),
                    }),
                ),
            );

            const passedBody: UserUpdate = mockUpdateUser.mock.calls[0][1];
            expect(passedBody).not.toHaveProperty('password');
            expect(passedBody).not.toHaveProperty('user_id');
            expect(passedBody.first_name).toBe(UPDATED_FIRST_NAME);
        });

        it('INCLUDES the password (and still no user_id) when a new password is typed', async () => {
            const user = SetupUser();
            ArmAdminUser();
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockResolvedValueOnce(MakeUserRead());
            mockUpdateUser.mockResolvedValueOnce(MakeUserRead());

            RenderWithProviders(<UsersUpdatePage />);

            const passwordField = await screen.findByLabelText(/Password/i);
            await user.type(passwordField, TYPED_PASSWORD);
            await user.click(screen.getByRole('button', { name: 'Save' }));

            await waitFor(() =>
                expect(mockUpdateUser).toHaveBeenCalledTimes(1),
            );

            const passedBody: UserUpdate = mockUpdateUser.mock.calls[0][1];
            expect(passedBody).toHaveProperty('password', TYPED_PASSWORD);
            expect(passedBody).not.toHaveProperty('user_id');
        });
    });

    describe('password handling', () => {
        it('never prefills or echoes the password after a fetch', async () => {
            ArmAdminUser();
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockResolvedValueOnce(MakeUserRead());

            RenderWithProviders(<UsersUpdatePage />);

            const passwordField = await screen.findByLabelText(/Password/i);
            expect(passwordField).toHaveAttribute('type', 'password');
            expect(passwordField).toHaveValue('');
            // The helper text documents the blank-keeps-current contract.
            expect(
                screen.getByText('Leave blank to keep the current password.'),
            ).toBeInTheDocument();
        });
    });

    describe('error handling', () => {
        it('shows a friendly "User not found" message on a 404 and stays unfetched', async () => {
            ArmAdminUser();
            const notFoundError = new ApiError({
                status: HTTP_NOT_FOUND,
                message: 'Not found',
            });
            // Confirm the REAL guard recognizes the error the page will narrow on.
            expect(IsApiError(notFoundError)).toBe(true);
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockRejectedValueOnce(notFoundError);

            RenderWithProviders(<UsersUpdatePage />);

            await waitFor(() =>
                expect(mockGetUser).toHaveBeenCalledWith(TARGET_USER_ID),
            );
            expect(
                await screen.findByText(/User not found/i),
            ).toBeInTheDocument();
            // fetched stays false -> the edit fields are never rendered.
            expect(screen.queryByLabelText(/First Name/i)).toBeNull();
            // A 404 is NOT a redirect.
            expect(mockReplace).not.toHaveBeenCalled();
        });

        it('redirects to /menu when the fetch is forbidden (403)', async () => {
            ArmAdminUser();
            const forbiddenError = new ApiError({
                status: HTTP_FORBIDDEN,
                message: 'Forbidden',
            });
            expect(IsApiError(forbiddenError)).toBe(true);
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockRejectedValueOnce(forbiddenError);

            RenderWithProviders(<UsersUpdatePage />);

            await waitFor(() =>
                expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE),
            );
        });
    });

    describe('navigation', () => {
        it('navigates to the user list after a successful Save & Exit (F3)', async () => {
            const user = SetupUser();
            ArmAdminUser();
            mockSearchParams = new URLSearchParams({ userId: TARGET_USER_ID });
            mockGetUser.mockResolvedValueOnce(MakeUserRead());
            mockUpdateUser.mockResolvedValueOnce(MakeUserRead());

            RenderWithProviders(<UsersUpdatePage />);

            await screen.findByLabelText(/First Name/i);
            await user.click(
                screen.getByRole('button', { name: 'Save & Exit' }),
            );

            await waitFor(() =>
                expect(mockPush).toHaveBeenCalledWith(USERS_ROUTE),
            );
        });
    });
});
