/** Sign-On page spec — legacy origin BMS COSGN00 / Tx CC00 / program COSGN00C. */

/*
 * signon.test.tsx -- Jest + React Testing Library component/integration spec for
 * the Sign-On page (`@/app/signon/page`, default export `SignonPage`), the modern
 * Next.js / Material UI replacement for the legacy 3270 signon screen BMS map
 * COSGN00 (CICS transaction CC00, COBOL program COSGN00C).
 *
 * The REAL page is rendered; only its collaborators are mocked -- `next/navigation`
 * (so navigation is observable, never real) and `@/lib/auth` (so `Login` is a
 * controllable jest.fn with NO real network). `@/lib/apiClient` is intentionally
 * left REAL: the page itself does not import it, but the `ErrorAlert` the page
 * renders narrows a caught login failure with `IsApiError`, so `ApiError` must be
 * the genuine class (an `instanceof` match) for a 401 to surface its message.
 *
 * Behavior asserted (ported 1:1 from COSGN00C): required-field edits with the
 * verbatim legacy messages (User ID before Password), role-based landing routing
 * (`user_type === 'A'` -> `/admin`, otherwise -> `/menu`; COSGN00C L230-240), a
 * surfaced server error on bad credentials without leaving `/signon`, and the
 * legacy ENTER=Sign-on key submitting the form.
 */

import SignonPage from '@/app/signon/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    SetupUser,
    MakeCurrentUser,
    MakeAdminUser,
} from '../testUtils';
import { Login } from '@/lib/auth';
import { ApiError } from '@/lib/apiClient';

/* ------------------------------------------------------------------------- */
/* Mocks (declared before importing the symbols they replace).               */
/* ------------------------------------------------------------------------- */

/*
 * next/navigation -- module-scope jest.fns carry the `mock` prefix required by
 * the jest hoisting rule so the (hoisted) factory may reference them. Each fn is
 * only read inside a DEFERRED arrow (`useRouter: () => ({ push: mockPush })`), so
 * it is evaluated at render time, after module initialization -- no TDZ risk.
 */
const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();

jest.mock('next/navigation', () => ({
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
    }),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/signon',
}));

/*
 * @/lib/auth -- auto-mock turns `Login` (and its siblings) into jest.fns. The
 * page only calls `Login`, so only `Login` is armed per test below. Return values
 * are re-armed each test with `mockResolvedValueOnce` / `mockRejectedValueOnce`
 * because `clearMocks: true` clears call history between tests.
 */
jest.mock('@/lib/auth');

/* ------------------------------------------------------------------------- */
/* Test constants (Ochs Test Rule: ALL_UPPERCASE with underscores).          */
/* ------------------------------------------------------------------------- */

/** Admin landing route -- legacy XCTL to COADM01C (COSGN00C L232). */
const ADMIN_ROUTE = '/admin';

/** Regular-user landing route -- legacy XCTL to COMEN01C (COSGN00C L237). */
const MENU_ROUTE = '/menu';

/** Accessible name of the primary submit control -- rendered label "Sign On". */
const SIGN_ON_BUTTON_NAME = /sign ?on|log ?in|submit|enter/i;

/** Seed-style admin id (README ADMIN001); a public sample, never a secret. */
const ADMIN_USER_ID = 'ADMIN001';

/** Seed-style regular id (README USER0001); a public sample, never a secret. */
const REGULAR_USER_ID = 'USER0001';

/**
 * Throwaway 8-char password used ONLY to prove it is forwarded to `Login`. It is
 * a clearly fake value (never any real or sample seed credential) and is never
 * persisted or logged; the field masks it (type="password") and stays within
 * maxLength 8.
 */
const THROWAWAY_PASSWORD = 'pw123456';

/** Verbatim empty-User-ID edit (COSGN00C L120), matched case-insensitively. */
const EMPTY_USER_ID_MESSAGE = /please enter user id/i;

/** Server login-failure message surfaced for an HTTP 401 (bad credentials). */
const LOGIN_FAILURE_MESSAGE = 'Wrong Password. Try again ...';

/**
 * Actionable message the client normalizes for a request that never reaches the
 * server -- a dropped network, a timeout, or a CORS / host-alias rejection (QA
 * finding M-11). The signon screen must surface this so the user is never left
 * without feedback.
 */
const NETWORK_ERROR_MESSAGE =
    'Unable to reach the server. Please check your network connection and try again.';

/* ------------------------------------------------------------------------- */
/* Suite.                                                                    */
/* ------------------------------------------------------------------------- */

describe('SignonPage', () => {
    it('renders the sign-on form with masked, length-bounded fields and a submit button', () => {
        RenderWithProviders(<SignonPage />);

        // Both fields are `required`, which appends "*" to the label, so the
        // label queries use a case-insensitive regex (exact string would miss).
        const userIdInput = screen.getByLabelText(/user id/i);
        const passwordInput = screen.getByLabelText(/password/i);

        expect(userIdInput).toBeInTheDocument();
        expect(passwordInput).toBeInTheDocument();

        // COSGN00.CPY USERIDI/PASSWDI PIC X(8) -> maxLength 8 forwarded to the DOM
        // `maxlength` attribute via FormField's slotProps.htmlInput.
        expect(userIdInput).toHaveAttribute('maxlength', '8');
        expect(passwordInput).toHaveAttribute('maxlength', '8');

        // The Password field must be masked (type="password"); a masked input
        // deliberately exposes NO textbox role.
        expect(passwordInput).toHaveAttribute('type', 'password');
        expect(screen.queryByRole('textbox', { name: /password/i })).toBeNull();

        // The 3270 "ENTER=Sign-on" action becomes a single primary submit button.
        expect(
            screen.getByRole('button', { name: SIGN_ON_BUTTON_NAME }),
        ).toBeInTheDocument();
    });

    it('exposes an accessible h1 heading and password-manager autocomplete tokens (N-01)', () => {
        RenderWithProviders(<SignonPage />);

        // Exactly one level-1 heading names the page, so assistive tech has a
        // single, correct document title anchor (QA N-01, "H1 hierarchy").
        const headings = screen.getAllByRole('heading', { level: 1 });
        expect(headings).toHaveLength(1);
        expect(headings[0]).toHaveTextContent(/card\s*demo sign on/i);

        // Identity fields carry the semantic autocomplete tokens so browsers and
        // password managers offer the right credentials (QA N-01, "autocomplete").
        expect(screen.getByLabelText(/user id/i)).toHaveAttribute(
            'autocomplete',
            'username',
        );
        expect(screen.getByLabelText(/password/i)).toHaveAttribute(
            'autocomplete',
            'current-password',
        );
    });

    it('shows the verbatim User ID edit and does not call the server when fields are empty', async () => {
        const user = SetupUser();
        RenderWithProviders(<SignonPage />);

        await user.click(
            screen.getByRole('button', { name: SIGN_ON_BUTTON_NAME }),
        );

        // COSGN00C PROCESS-ENTER-KEY (L117-140): the User ID edit runs first and
        // is surfaced verbatim in the ErrorAlert.
        expect(
            await screen.findByText(EMPTY_USER_ID_MESSAGE),
        ).toBeInTheDocument();

        // With no credentials the server-side Login helper is never invoked.
        expect(Login).not.toHaveBeenCalled();
    });

    it('routes an administrator to /admin after a successful login', async () => {
        const user = SetupUser();
        (Login as jest.Mock).mockResolvedValueOnce(MakeAdminUser());
        RenderWithProviders(<SignonPage />);

        await user.type(screen.getByLabelText(/user id/i), ADMIN_USER_ID);
        await user.type(screen.getByLabelText(/password/i), THROWAWAY_PASSWORD);
        await user.click(
            screen.getByRole('button', { name: SIGN_ON_BUTTON_NAME }),
        );

        // Credentials are forwarded to the server-side Login helper exactly as
        // typed (LoginRequest { user_id, password }).
        await waitFor(() =>
            expect(Login).toHaveBeenCalledWith(
                expect.objectContaining({
                    user_id: ADMIN_USER_ID,
                    password: THROWAWAY_PASSWORD,
                }),
            ),
        );

        // COSGN00C L230-232: user_type 'A' -> admin menu (COADM01C) -> /admin.
        await waitFor(() => expect(mockPush).toHaveBeenCalledWith(ADMIN_ROUTE));
        expect(mockPush).not.toHaveBeenCalledWith(MENU_ROUTE);
    });

    it('routes a regular user to /menu after a successful login', async () => {
        const user = SetupUser();
        (Login as jest.Mock).mockResolvedValueOnce(MakeCurrentUser());
        RenderWithProviders(<SignonPage />);

        await user.type(screen.getByLabelText(/user id/i), REGULAR_USER_ID);
        await user.type(screen.getByLabelText(/password/i), THROWAWAY_PASSWORD);
        await user.click(
            screen.getByRole('button', { name: SIGN_ON_BUTTON_NAME }),
        );

        await waitFor(() =>
            expect(Login).toHaveBeenCalledWith(
                expect.objectContaining({
                    user_id: REGULAR_USER_ID,
                    password: THROWAWAY_PASSWORD,
                }),
            ),
        );

        // COSGN00C L237: user_type 'U' -> user menu (COMEN01C) -> /menu.
        await waitFor(() => expect(mockPush).toHaveBeenCalledWith(MENU_ROUTE));
        expect(mockPush).not.toHaveBeenCalledWith(ADMIN_ROUTE);
    });

    it('surfaces the server error and does not navigate on invalid credentials', async () => {
        const user = SetupUser();
        // Real ApiError (kept unmocked) so ErrorAlert's IsApiError instanceof
        // check narrows it and renders the message; mirrors a backend HTTP 401.
        (Login as jest.Mock).mockRejectedValueOnce(
            new ApiError({ status: 401, message: LOGIN_FAILURE_MESSAGE }),
        );
        RenderWithProviders(<SignonPage />);

        await user.type(screen.getByLabelText(/user id/i), REGULAR_USER_ID);
        await user.type(screen.getByLabelText(/password/i), THROWAWAY_PASSWORD);
        await user.click(
            screen.getByRole('button', { name: SIGN_ON_BUTTON_NAME }),
        );

        // The rejected login is surfaced in the ErrorAlert (MUI Alert role="alert").
        const errorAlert = await screen.findByRole('alert');
        expect(errorAlert).toHaveTextContent(/wrong password/i);

        // A failed signon must NOT leave the /signon screen (no navigation).
        expect(mockPush).not.toHaveBeenCalled();
        expect(mockReplace).not.toHaveBeenCalled();
    });

    it('surfaces an actionable network/CORS error and does not navigate (M-11)', async () => {
        const user = SetupUser();
        // A CORS / host-alias rejection or dropped connection is normalized by the
        // client into an ApiError with status 0 and the actionable network message;
        // the signon screen must show it (never leave the user without feedback).
        (Login as jest.Mock).mockRejectedValueOnce(
            new ApiError({ status: 0, message: NETWORK_ERROR_MESSAGE }),
        );
        RenderWithProviders(<SignonPage />);

        await user.type(screen.getByLabelText(/user id/i), REGULAR_USER_ID);
        await user.type(screen.getByLabelText(/password/i), THROWAWAY_PASSWORD);
        await user.click(
            screen.getByRole('button', { name: SIGN_ON_BUTTON_NAME }),
        );

        // The actionable network message is surfaced in the ErrorAlert.
        const errorAlert = await screen.findByRole('alert');
        expect(errorAlert).toHaveTextContent(/unable to reach the server/i);

        // A network failure must NOT navigate away from /signon.
        expect(mockPush).not.toHaveBeenCalled();
        expect(mockReplace).not.toHaveBeenCalled();
    });

    it('submits the form when Enter is pressed in the password field', async () => {
        const user = SetupUser();
        (Login as jest.Mock).mockResolvedValueOnce(MakeCurrentUser());
        RenderWithProviders(<SignonPage />);

        await user.type(screen.getByLabelText(/user id/i), REGULAR_USER_ID);
        // The form is a `Box component="form"`, so the legacy ENTER=Sign-on key
        // (typed after the password) triggers submission -- no button click.
        await user.type(
            screen.getByLabelText(/password/i),
            `${THROWAWAY_PASSWORD}{Enter}`,
        );

        await waitFor(() =>
            expect(Login).toHaveBeenCalledWith(
                expect.objectContaining({
                    user_id: REGULAR_USER_ID,
                    password: THROWAWAY_PASSWORD,
                }),
            ),
        );
    });
});
