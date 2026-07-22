/**
 * auth.test.ts -- Jest + jsdom unit suite for the frontend client-side
 * session / identity helper `@/lib/auth` (frontend/src/lib/auth.ts).
 *
 * WHAT IS VERIFIED
 *   - `Login`: forwards the credentials to the (mocked) `AuthApi.Login`, builds
 *     and returns the NON-SENSITIVE `CurrentUser`, persists it under
 *     `SESSION_USER_STORAGE_KEY`, stores the bearer token under
 *     `ACCESS_TOKEN_STORAGE_KEY` ONLY when the response carries one
 *     (JWT-alternative mode), and PROPAGATES auth errors without persisting;
 *   - `Logout`: delegates client-state teardown to `ClearStoredAuth` and then
 *     performs the hard redirect to `/signon`;
 *   - `GetCurrentUser`: returns the parsed stored user, `null` when absent, and
 *     `null` (never throwing) when the stored JSON is malformed;
 *   - `GetRole` / `IsAdmin`: report the `'A'`/`'U'` role (or `null`), with
 *     `IsAdmin` true only for administrators;
 *   - null-safety of the read paths when storage is empty.
 *
 * TRACEABILITY: `auth.ts` is the stateless replacement for the legacy CICS
 * CARDDEMO-COMMAREA (copybook COCOM01Y) identity/role propagation. `Login` /
 * `Logout` mirror the signon screen COSGN00 / COSGN00.bms (CICS tx CC00); the
 * role helpers mirror the SEC-USR-TYPE flag of the user-security record
 * CSUSR01Y.cpy ('A' = admin -> admin menu COADM01 / CA00, 'U' = regular ->
 * user menu COMEN01 / CM00). SECURITY: the legacy SEC-USR-PWD PIC X(08) was
 * plaintext; here the password is inbound-only to `AuthApi.Login` and is NEVER
 * persisted or echoed -- these tests assert that invariant explicitly.
 *
 * MOCKING NOTE (do not "simplify" away): `@/lib/apiClient` is replaced with an
 * explicit factory so `AuthApi.Login` and `ClearStoredAuth` are controllable
 * jest fns while the two storage-key CONSTANTS keep their real string values.
 * `auth.ts` imports those SAME symbols via its relative `./apiClient` specifier,
 * which the jest `moduleNameMapper` (`^@/(.*)$` -> `<rootDir>/src/$1`) resolves
 * to the identical module file -- so the mock deterministically replaces the
 * dependency `auth.ts` consumes, and the write-key always matches the read-key.
 * There is NO real network here. Greenfield test infra -- no legacy test origin.
 */

// --- Phase A: mock `@/lib/apiClient` with an explicit factory (hoisted) ------
jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    AuthApi: { Login: jest.fn() },
    ClearStoredAuth: jest.fn(),
    SESSION_USER_STORAGE_KEY: 'carddemo_user',
    ACCESS_TOKEN_STORAGE_KEY: 'carddemo_access_token',
}));

import { Login, Logout, GetCurrentUser, GetRole, IsAdmin } from '@/lib/auth';
import {
    AuthApi,
    ClearStoredAuth,
    SESSION_USER_STORAGE_KEY,
    ACCESS_TOKEN_STORAGE_KEY,
} from '@/lib/apiClient';
import type { CurrentUser, LoginResponse } from '@/types';
import { MakeCurrentUser, MakeAdminUser } from '../testUtils';

/* --------------------------------------------------------------------------- */
/* Typed handles to the mocked collaborators (identity-stable across tests --  */
/* `clearMocks: true` clears their call history but never the fn objects).     */
/* --------------------------------------------------------------------------- */

const mockAuthLogin = jest.mocked(AuthApi.Login);
const mockClearStoredAuth = jest.mocked(ClearStoredAuth);

/**
 * Throwaway login password used ONLY to assert it is forwarded to
 * `AuthApi.Login`; it is never a real credential and is never persisted.
 */
const THROWAWAY_PASSWORD = 'irrelevant-test-value';

/* --------------------------------------------------------------------------- */
/* Test helpers (Ochs rule: PascalCase functions).                             */
/* --------------------------------------------------------------------------- */

/**
 * Replaces `window.location` with a settable plain-object stub so `Logout`'s
 * `window.location.href = '/signon'` assignment is observable (and produces no
 * jsdom "not implemented: navigation" noise). `configurable: true` lets it be
 * redefined every test; the returned reference is read to assert `.href`.
 *
 * @param pathname - The `pathname` the stubbed location should report.
 * @returns The mutable location stub (read `.href` after the call under test).
 */
function StubLocation(pathname: string): { href: string; pathname: string } {
    const locationStub = { href: '', pathname };
    Object.defineProperty(window, 'location', {
        value: locationStub,
        writable: true,
        configurable: true,
    });
    return locationStub;
}

/**
 * Builds a valid `LoginResponse` fixture from the shared regular-user identity
 * fixture, guaranteeing the required `first_name` / `last_name` strings and
 * merging any extra fields (e.g. `access_token`) supplied by the caller.
 *
 * @param overrides - Partial `LoginResponse` fields to merge over the defaults.
 * @returns A fully-populated `LoginResponse`.
 */
function MakeLoginResponse(overrides?: Partial<LoginResponse>): LoginResponse {
    const baseResponse: LoginResponse = {
        ...MakeCurrentUser(),
        first_name: 'Test',
        last_name: 'User',
    };
    return { ...baseResponse, ...overrides };
}

/* --------------------------------------------------------------------------- */
/* Per-test reset: jsdom storage + location. `clearMocks: true` (jest config)  */
/* already clears the mock call history before each test.                      */
/* --------------------------------------------------------------------------- */

beforeEach(() => {
    localStorage.clear();
    StubLocation('/menu');
});

describe('Login', () => {
    it('forwards the credentials to AuthApi.Login and returns the CurrentUser', async () => {
        const loginResponse = MakeLoginResponse();
        mockAuthLogin.mockResolvedValueOnce(loginResponse);
        const credentials = { user_id: 'USER0001', password: THROWAWAY_PASSWORD };

        const result = await Login(credentials);

        expect(mockAuthLogin).toHaveBeenCalledTimes(1);
        expect(mockAuthLogin).toHaveBeenCalledWith(credentials);
        expect(result).toEqual({
            user_id: 'USER0001',
            user_type: 'U',
            first_name: 'Test',
            last_name: 'User',
        });
        // Security invariant: the resolved principal never carries a secret.
        expect(result).not.toHaveProperty('password');
        expect(result).not.toHaveProperty('access_token');
    });

    it('persists the CurrentUser under SESSION_USER_STORAGE_KEY with no password', async () => {
        mockAuthLogin.mockResolvedValueOnce(MakeLoginResponse());
        const credentials = { user_id: 'USER0001', password: THROWAWAY_PASSWORD };

        const currentUser = await Login(credentials);

        const rawStored = localStorage.getItem(SESSION_USER_STORAGE_KEY);
        expect(rawStored).not.toBeNull();
        const stored = JSON.parse(rawStored as string) as CurrentUser;
        expect(stored).toEqual(currentUser);
        expect(stored).not.toHaveProperty('password');
    });

    it('stores the access token when the response carries one (JWT mode)', async () => {
        mockAuthLogin.mockResolvedValueOnce(
            MakeLoginResponse({ access_token: 'jwt-abc', token_type: 'bearer' }),
        );
        const credentials = { user_id: 'USER0001', password: THROWAWAY_PASSWORD };

        await Login(credentials);

        expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBe('jwt-abc');
    });

    it('stores no access token in the session baseline (no token in response)', async () => {
        mockAuthLogin.mockResolvedValueOnce(MakeLoginResponse());
        const credentials = { user_id: 'USER0001', password: THROWAWAY_PASSWORD };

        await Login(credentials);

        expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    });

    it('propagates authentication errors and persists nothing', async () => {
        mockAuthLogin.mockRejectedValueOnce(new Error('invalid credentials'));
        const credentials = { user_id: 'USER0001', password: THROWAWAY_PASSWORD };

        await expect(Login(credentials)).rejects.toThrow('invalid credentials');

        expect(localStorage.getItem(SESSION_USER_STORAGE_KEY)).toBeNull();
        expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    });
});

describe('Logout', () => {
    it('delegates client teardown to ClearStoredAuth and redirects to /signon', () => {
        localStorage.setItem(
            SESSION_USER_STORAGE_KEY,
            JSON.stringify(MakeCurrentUser()),
        );
        const locationStub = StubLocation('/menu');

        Logout();

        // Collaboration only: ClearStoredAuth is a mock here, so it does NOT
        // clear jsdom storage -- the real clearing is covered by apiClient.test.ts.
        expect(mockClearStoredAuth).toHaveBeenCalledTimes(1);
        expect(locationStub.href).toBe('/signon');
    });
});

describe('GetCurrentUser', () => {
    it('returns the parsed stored user when present', () => {
        const adminUser = MakeAdminUser();
        localStorage.setItem(SESSION_USER_STORAGE_KEY, JSON.stringify(adminUser));

        expect(GetCurrentUser()).toEqual(adminUser);
    });

    it('returns null when no user is stored', () => {
        expect(GetCurrentUser()).toBeNull();
    });

    it('returns null (does not throw) when the stored JSON is malformed', () => {
        localStorage.setItem(SESSION_USER_STORAGE_KEY, 'not-json{');

        // The module catches the specific SyntaxError and returns null.
        expect(() => GetCurrentUser()).not.toThrow();
        expect(GetCurrentUser()).toBeNull();
    });
});

describe('GetRole & IsAdmin', () => {
    it("GetRole returns 'U' for a regular user", () => {
        localStorage.setItem(
            SESSION_USER_STORAGE_KEY,
            JSON.stringify(MakeCurrentUser()),
        );

        expect(GetRole()).toBe('U');
    });

    it("GetRole returns 'A' for an administrator", () => {
        localStorage.setItem(
            SESSION_USER_STORAGE_KEY,
            JSON.stringify(MakeAdminUser()),
        );

        expect(GetRole()).toBe('A');
    });

    it('GetRole returns null when no user is stored', () => {
        expect(GetRole()).toBeNull();
    });

    it('IsAdmin is true for an administrator', () => {
        localStorage.setItem(
            SESSION_USER_STORAGE_KEY,
            JSON.stringify(MakeAdminUser()),
        );

        expect(IsAdmin()).toBe(true);
    });

    it('IsAdmin is false for a regular user', () => {
        localStorage.setItem(
            SESSION_USER_STORAGE_KEY,
            JSON.stringify(MakeCurrentUser()),
        );

        expect(IsAdmin()).toBe(false);
    });

    it('IsAdmin is false when no user is stored', () => {
        expect(IsAdmin()).toBe(false);
    });
});

describe('SSR & null-safety', () => {
    it('read helpers are null-safe with empty storage (no throw)', () => {
        // localStorage was cleared in beforeEach; exercise every read path.
        expect(() => {
            GetCurrentUser();
            GetRole();
            IsAdmin();
        }).not.toThrow();
        expect(GetCurrentUser()).toBeNull();
        expect(GetRole()).toBeNull();
        expect(IsAdmin()).toBe(false);
    });

    it('treats an empty-string stored value as no user (null) without parsing', () => {
        localStorage.setItem(SESSION_USER_STORAGE_KEY, '');

        expect(GetCurrentUser()).toBeNull();
        expect(GetRole()).toBeNull();
        expect(IsAdmin()).toBe(false);
    });
});

