/**
 * Add User page spec — legacy origin BMS COUSR01 / Tx CU01 /
 * program COUSR01C. Admin-only; password on create only.
 */

/*
 * Component / integration spec for the modern Add-User page
 * (`@/app/users/add/page`, default export `UsersAddPage`) — the redesigned
 * replacement for the legacy BMS map COUSR01 (CICS transaction CU01, COBOL
 * program COUSR01C).
 *
 * The REAL page is rendered against a mocked `next/navigation`, a mocked
 * `@/lib/auth`, and a mocked `UsersApi`; there is NO real network. The typed
 * error helpers `ApiError` and `IsApiError` are deliberately kept REAL so the
 * page's `403 -> /menu` admin branch — which relies on `error instanceof
 * ApiError` — is exercised faithfully.
 *
 * Field fidelity traces to app/cpy-bms/COUSR01.CPY: FNAMEI / LNAMEI PIC X(20),
 * USERIDI PIC X(8), PASSWDI PIC X(8) (masked, ATTRB=(DRK,...)), USRTYPEI PIC
 * X(1) with the inline hint '(A=Admin, U=User)'. The PF footer 'ENTER=Add User
 * F3=Back F4=Clear F12=Exit' maps to the Add User / Back / Clear buttons.
 */

// ---------------------------------------------------------------------------
// Router mock. The `mock*` prefix lets these fns be referenced inside the
// hoisted `jest.mock` factory (Jest permits out-of-scope vars named `mock*`).
// ---------------------------------------------------------------------------
const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();

jest.mock('next/navigation', () => ({
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        forward: jest.fn(),
        refresh: jest.fn(),
        prefetch: jest.fn(),
    }),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/users/add',
}));

// Auto-mock the client-side auth helpers so the admin gate is armed per-test.
jest.mock('@/lib/auth');

// Keep the apiClient module REAL (ApiError + IsApiError MUST stay real for the
// page's instanceof-based 403 branch), replacing ONLY the network-bound UsersApi.
jest.mock('@/lib/apiClient', () => {
    const actualModule = jest.requireActual('@/lib/apiClient');
    return {
        ...actualModule,
        UsersApi: {
            ListUsers: jest.fn(),
            AddUser: jest.fn(),
            GetUser: jest.fn(),
            UpdateUser: jest.fn(),
            DeleteUser: jest.fn(),
        },
    };
});

import UsersAddPage from '@/app/users/add/page';
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
import type { UserCreate, UserRead } from '@/types';

/* ------------------------------------------------------------------------- */
/* Typed handles to the mocked collaborators (identity-stable across tests -- */
/* `clearMocks: true` clears their call history but never the fn objects,     */
/* and never their implementation, so each test re-arms what it needs).       */
/* ------------------------------------------------------------------------- */

const mockIsAdmin = jest.mocked(IsAdmin);
const mockGetCurrentUser = jest.mocked(GetCurrentUser);
const mockAddUser = jest.mocked(UsersApi.AddUser);

/* ------------------------------------------------------------------------- */
/* Constants (Ochs rule: ALL_UPPERCASE with underscores). Routes mirror the   */
/* real page; the VALID_* values are throwaway, non-secret test fixtures.     */
/* ------------------------------------------------------------------------- */

/** Route the client gate / server 403 redirects to (page MENU_ROUTE). */
const MENU_ROUTE = '/menu';

/** Post-success and Back landing route (page USERS_LIST_ROUTE). */
const USERS_LIST_ROUTE = '/users';

const VALID_FIRST_NAME = 'John';
const VALID_LAST_NAME = 'Doe';
const VALID_USER_ID = 'NEWUSER1';

/** Throwaway, obviously-fake 8-char test password — never a real credential. */
const VALID_PASSWORD = 'Pass1234';

/** Chosen User Type ('U' = regular user); `as const` gives the 'A' | 'U' literal. */
const VALID_USER_TYPE = 'U' as const;

/** HTTP status codes exercised by the two failure paths. */
const FORBIDDEN_STATUS = 403;
const SERVER_ERROR_STATUS = 500;

/** Message surfaced by the non-403 (generic failure) path. */
const SERVER_ERROR_MESSAGE = 'Internal server error';

/**
 * The snake_case wire DTO the page must POST for the valid fixture above. Typed
 * as {@link UserCreate} so the create payload's shape is checked at compile time.
 */
const EXPECTED_PAYLOAD: UserCreate = {
    user_id: VALID_USER_ID,
    first_name: VALID_FIRST_NAME,
    last_name: VALID_LAST_NAME,
    password: VALID_PASSWORD,
    user_type: VALID_USER_TYPE,
};

/**
 * The read model the backend returns on create. Typed as {@link UserRead}, which
 * has NO `password` field — the create response never carries the credential.
 */
const CREATED_USER: UserRead = {
    user_id: VALID_USER_ID,
    first_name: VALID_FIRST_NAME,
    last_name: VALID_LAST_NAME,
    user_type: VALID_USER_TYPE,
};

/* ------------------------------------------------------------------------- */
/* Test helpers (Ochs rule: PascalCase names, single-purpose, <=4 params).    */
/* ------------------------------------------------------------------------- */

/** Arms the auth mocks so the client admin gate ADMITS an administrator. */
function ArmAdmin(): void {
    mockIsAdmin.mockReturnValue(true);
    mockGetCurrentUser.mockReturnValue(MakeAdminUser());
}

/** Arms the auth mocks so the client admin gate DENIES a regular user. */
function ArmNonAdmin(): void {
    mockIsAdmin.mockReturnValue(false);
    mockGetCurrentUser.mockReturnValue(MakeCurrentUser());
}

/** Renders the real Add-User page inside the shared MUI theme provider. */
function RenderAddUserPage(): void {
    RenderWithProviders(<UsersAddPage />);
}

/**
 * Types valid values into all five fields — the four text/password inputs plus
 * the User Type dropdown — awaiting the admin form first so it is present.
 *
 * @param user - The user-event session dispatching the interactions.
 */
async function FillValidForm(
    user: ReturnType<typeof SetupUser>,
): Promise<void> {
    const firstNameInput = await screen.findByRole('textbox', {
        name: /First Name/i,
    });
    await user.type(firstNameInput, VALID_FIRST_NAME);
    await user.type(
        screen.getByRole('textbox', { name: /Last Name/i }),
        VALID_LAST_NAME,
    );
    await user.type(
        screen.getByRole('textbox', { name: /User ID/i }),
        VALID_USER_ID,
    );
    await user.type(screen.getByLabelText(/Password/i), VALID_PASSWORD);
    // MUI Select: open the combobox, then choose the 'User' (value 'U') option.
    await user.click(screen.getByRole('combobox', { name: /User Type/i }));
    await user.click(await screen.findByRole('option', { name: 'User' }));
}

/* ------------------------------------------------------------------------- */
/* Suite.                                                                     */
/* ------------------------------------------------------------------------- */

describe('UsersAddPage', () => {
    beforeEach(() => {
        // `clearMocks` resets only call history, not implementations, so re-arm
        // the admin gate each test (the non-admin case overrides this default).
        ArmAdmin();
    });

    it('redirects a non-admin to the menu and renders no form', async () => {
        ArmNonAdmin();

        RenderAddUserPage();

        // Client gate: IsAdmin() === false -> router.replace('/menu').
        await waitFor(() =>
            expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE),
        );
        // The component returns null for non-admins — nothing is rendered.
        expect(
            screen.queryByRole('heading', { name: 'Add User' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: /Add User/i }),
        ).not.toBeInTheDocument();
    });

    it('renders every field with COUSR01 labels, lengths, and options for an admin', async () => {
        const user = SetupUser();
        RenderAddUserPage();

        const firstNameInput = await screen.findByRole('textbox', {
            name: /First Name/i,
        });
        const lastNameInput = screen.getByRole('textbox', {
            name: /Last Name/i,
        });
        const userIdInput = screen.getByRole('textbox', { name: /User ID/i });
        const passwordInput = screen.getByLabelText(/Password/i);

        // Field lengths trace to COUSR01.CPY: FNAMEI/LNAMEI X(20), USERIDI/PASSWDI X(8).
        expect(firstNameInput).toHaveAttribute('maxlength', '20');
        expect(lastNameInput).toHaveAttribute('maxlength', '20');
        expect(userIdInput).toHaveAttribute('maxlength', '8');
        expect(passwordInput).toHaveAttribute('maxlength', '8');
        // PASSWD ATTRB=(DRK,...) -> the input is masked.
        expect(passwordInput).toHaveAttribute('type', 'password');

        // User Type is a Select exposing the legacy '(A=Admin, U=User)' options.
        const userTypeSelect = screen.getByRole('combobox', {
            name: /User Type/i,
        });
        expect(userTypeSelect).toBeInTheDocument();
        await user.click(userTypeSelect);
        expect(
            await screen.findByRole('option', { name: 'Admin' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('option', { name: 'User' }),
        ).toBeInTheDocument();
    });

    it('blocks submission and shows a validation alert when fields are empty', async () => {
        const user = SetupUser();
        RenderAddUserPage();

        const submitButton = await screen.findByRole('button', {
            name: /Add User/i,
        });
        await user.click(submitButton);

        // ValidateForm rejects the empty form with the first-field message, shown
        // via ErrorAlert (MUI Alert, role="alert"); AddUser is never called.
        const validationAlert = await screen.findByRole('alert');
        expect(validationAlert).toHaveTextContent('First Name is required.');
        expect(mockAddUser).not.toHaveBeenCalled();
    });

    it('creates the user (201) then clears the form and returns to the list', async () => {
        const user = SetupUser();
        mockAddUser.mockResolvedValueOnce(CREATED_USER);

        RenderAddUserPage();
        await FillValidForm(user);
        await user.click(screen.getByRole('button', { name: /Add User/i }));

        // The page maps camelCase state to the snake_case UserCreate DTO — all
        // five keys, including the write-only password and user_type 'U'.
        await waitFor(() =>
            expect(mockAddUser).toHaveBeenCalledWith(
                expect.objectContaining(EXPECTED_PAYLOAD),
            ),
        );
        // Success handling: navigation back to the user list.
        await waitFor(() =>
            expect(mockPush).toHaveBeenCalledWith(USERS_LIST_ROUTE),
        );
        // ...and the form is cleared back to its initial state.
        expect(
            screen.getByRole('textbox', { name: /First Name/i }),
        ).toHaveValue('');
        // The returned UserRead carries no password, and none is surfaced.
        expect(screen.queryByDisplayValue(VALID_PASSWORD)).toBeNull();
    });

    it('redirects to the menu when the create is forbidden (403)', async () => {
        const user = SetupUser();
        const forbiddenError = new ApiError({
            status: FORBIDDEN_STATUS,
            message: 'Forbidden',
        });
        // The REAL IsApiError must recognize the REAL ApiError — this is exactly
        // the contract the page's catch branch (IsApiError && status===403) relies on.
        expect(IsApiError(forbiddenError)).toBe(true);
        mockAddUser.mockRejectedValueOnce(forbiddenError);

        RenderAddUserPage();
        await FillValidForm(user);
        await user.click(screen.getByRole('button', { name: /Add User/i }));

        await waitFor(() =>
            expect(mockReplace).toHaveBeenCalledWith(MENU_ROUTE),
        );
    });

    it('shows an error alert (no redirect) for a non-403 failure', async () => {
        const user = SetupUser();
        mockAddUser.mockRejectedValueOnce(
            new ApiError({
                status: SERVER_ERROR_STATUS,
                message: SERVER_ERROR_MESSAGE,
            }),
        );

        RenderAddUserPage();
        await FillValidForm(user);
        await user.click(screen.getByRole('button', { name: /Add User/i }));

        // A non-403 ApiError surfaces through ErrorAlert; there is NO redirect.
        const errorAlert = await screen.findByRole('alert');
        expect(errorAlert).toHaveTextContent(SERVER_ERROR_MESSAGE);
        expect(mockReplace).not.toHaveBeenCalled();
    });

    it('resets every field to empty when Clear is pressed', async () => {
        const user = SetupUser();
        RenderAddUserPage();

        const firstNameInput = await screen.findByRole('textbox', {
            name: /First Name/i,
        });
        const lastNameInput = screen.getByRole('textbox', {
            name: /Last Name/i,
        });
        const userIdInput = screen.getByRole('textbox', { name: /User ID/i });
        const passwordInput = screen.getByLabelText(/Password/i);

        await user.type(firstNameInput, VALID_FIRST_NAME);
        await user.type(lastNameInput, VALID_LAST_NAME);
        await user.type(userIdInput, VALID_USER_ID);
        await user.type(passwordInput, VALID_PASSWORD);

        // F4 = Clear resets to INITIAL_FORM_STATE (every field empty).
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        expect(firstNameInput).toHaveValue('');
        expect(lastNameInput).toHaveValue('');
        expect(userIdInput).toHaveValue('');
        expect(passwordInput).toHaveValue('');
    });

    it('navigates back to the user list when Back is pressed', async () => {
        const user = SetupUser();
        RenderAddUserPage();

        // F3 = Back returns to the user list via router.push('/users').
        const backButton = await screen.findByRole('button', { name: 'Back' });
        await user.click(backButton);

        expect(mockPush).toHaveBeenCalledWith(USERS_LIST_ROUTE);
    });

    it('masks the password and never echoes it back after submit', async () => {
        const user = SetupUser();
        mockAddUser.mockResolvedValueOnce(CREATED_USER);

        RenderAddUserPage();
        // Masked at rest (PASSWD ATTRB=(DRK,...) in COUSR01.bms).
        const passwordInput = await screen.findByLabelText(/Password/i);
        expect(passwordInput).toHaveAttribute('type', 'password');

        await FillValidForm(user);
        await user.click(screen.getByRole('button', { name: /Add User/i }));

        await waitFor(() =>
            expect(mockPush).toHaveBeenCalledWith(USERS_LIST_ROUTE),
        );
        // The plaintext password is never retained in a field nor rendered as text.
        expect(screen.queryByDisplayValue(VALID_PASSWORD)).toBeNull();
        expect(document.body).not.toHaveTextContent(VALID_PASSWORD);
    });
});
