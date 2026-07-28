'use client';

/*
 * auth.ts -- Client-side session / identity helpers for the CardDemo frontend.
 *
 * TRACEABILITY: this module replaces the CICS COCOM01Y COMMAREA identity/role
 * propagation with stateless client-side session context; the authoritative
 * session is the HTTP-only `carddemo_session` cookie set by the backend.
 *
 * On the mainframe every screen transition (signon COSGN00 -> user menu COMEN01
 * or admin menu COADM01) carried the signed-on user's identity and role
 * (CDEMO-USER-ID, CDEMO-USER-TYPE) forward in the CARDDEMO-COMMAREA so each
 * program knew who was acting and whether admin screens were allowed. There is
 * no COMMAREA here: the real session lives in the HTTP-only cookie (unreadable
 * by JavaScript, so it cannot be tampered with from the client), and only the
 * NON-SENSITIVE `CurrentUser` context (user id, names, role) is mirrored in
 * `localStorage` so the SPA can render the correct menu without a round-trip.
 *
 * SECURITY (QA finding M-10): no authentication credential is ever placed in
 * `localStorage`. The SPA relies solely on the HTTP-only session cookie; the
 * previous optional JWT bearer-in-localStorage path was removed because a token
 * in JavaScript-reachable storage is exfiltratable by XSS. Only the
 * non-sensitive identity mirror below is persisted.
 *
 * SECURITY: the role helpers below (`GetRole`, `IsAdmin`) drive UI convenience
 * only -- they hide admin-only screens (COADM01, COUSR00-03) from regular
 * users. They are NEVER the authorization boundary; the backend independently
 * enforces role via its `require_admin` dependency, so a tampered
 * `localStorage` value cannot grant real access. (AAP Section 0.4.1, 0.5.3.)
 */

import type { CurrentUser, LoginRequest, LoginResponse } from '@/types';

import {
    AuthApi,
    ClearStoredAuth,
    IsApiError,
    SESSION_USER_STORAGE_KEY,
} from './apiClient';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores).             */
/* ------------------------------------------------------------------------- */

/**
 * Name of the backend HTTP-only session cookie. JavaScript cannot read this
 * cookie (that is the point -- it is `HttpOnly`), so this constant is
 * documentation of the contract and a single place to coordinate the name if
 * any same-name client behavior is ever needed. Matches the backend
 * `settings.SESSION_COOKIE_NAME`.
 */
export const SESSION_COOKIE_NAME = 'carddemo_session';

/**
 * Maximum number of server-side logout (session-revocation) attempts before
 * {@link Logout} gives up and reports failure (QA Issue 2 / CWE-613). Sign-out is
 * a security operation: the SPA must not claim the user is signed out until the
 * backend confirms the HTTP-only session cookie was invalidated, so a transient
 * failure is RETRIED a bounded number of times rather than silently ignored.
 */
const LOGOUT_MAX_ATTEMPTS = 3;

/**
 * Delay, in milliseconds, between successive logout-revocation retries. Kept
 * short so a genuine sign-out still feels responsive while still giving a
 * momentarily unreachable backend a chance to recover.
 */
const LOGOUT_RETRY_DELAY_MS = 500;

/**
 * Fallback message used when the logout round-trip rejects with a value that is
 * not an `Error`, so {@link Logout} always propagates a real `Error` describing
 * an unconfirmed sign-out.
 */
const LOGOUT_FAILED_MESSAGE =
    'Sign-out could not be confirmed by the server. Your session may still be ' +
    'active. Please try again.';

/**
 * Resolves after `delayMs` milliseconds. A tiny SSR-safe helper used to space out
 * the bounded logout retries (setTimeout exists in both the browser and Node, so
 * no `window` guard is required).
 *
 * @param delayMs - How long to wait before resolving, in milliseconds.
 * @returns A promise that resolves once the delay elapses.
 */
function Delay(delayMs: number): Promise<void> {
    return new Promise((resolve) => {
        setTimeout(resolve, delayMs);
    });
}

/* ------------------------------------------------------------------------- */
/* Private storage helpers (small, SSR-guarded).                             */
/*                                                                           */
/* The session cookie is HTTP-only, so JS persists only the non-sensitive    */
/* CurrentUser context (user_id, names, user_type) for rendering decisions.  */
/* Every browser-API access is guarded with `typeof window !== 'undefined'`  */
/* so these helpers are safe during Next.js SSR / prerender.                 */
/* ------------------------------------------------------------------------- */

/**
 * Persists the non-sensitive {@link CurrentUser} context under the shared
 * storage key so later renders (and page reloads) can restore the identity
 * without another network call. No secret is ever written here. Guarded for
 * SSR; a no-op on the server where `window` is absent.
 *
 * @param currentUser The resolved principal (id, role, and optional names).
 */
function StoreCurrentUser(currentUser: CurrentUser): void {
    if (typeof window === 'undefined') {
        return;
    }
    window.localStorage.setItem(
        SESSION_USER_STORAGE_KEY,
        JSON.stringify(currentUser),
    );
}

/**
 * Reads and deserializes the persisted {@link CurrentUser} context. Returns
 * `null` when nothing is stored, when running on the server (no `window`), or
 * when the stored value is malformed. Malformed JSON is handled by catching
 * the specific `SyntaxError` only -- any other (unexpected) error is
 * re-thrown rather than silently swallowed, per the Ochs error-handling rule.
 *
 * @returns The stored principal, or `null` when unavailable / unparseable.
 */
function ReadStoredUser(): CurrentUser | null {
    if (typeof window === 'undefined') {
        return null;
    }
    const raw = window.localStorage.getItem(SESSION_USER_STORAGE_KEY);
    if (!raw) {
        return null;
    }
    try {
        return JSON.parse(raw) as CurrentUser;
    } catch (error) {
        if (error instanceof SyntaxError) {
            return null;
        }
        throw error;
    }
}

/* ------------------------------------------------------------------------- */
/* Public identity helpers (Ochs PascalCase functions).                      */
/* ------------------------------------------------------------------------- */

/**
 * Signs a user in. Origin: COSGN00C / COSGN00.bms (CICS tx CC00).
 *
 * Delegates the HTTP call to {@link AuthApi.Login}; the backend sets the
 * HTTP-only `carddemo_session` cookie (the sole authentication credential) and
 * only the non-sensitive identity is mirrored to `localStorage` for UI
 * decisions. Any `access_token` the backend may return (JWT-alternative mode) is
 * deliberately IGNORED by the browser SPA and never stored (QA finding M-10).
 *
 * The password is used solely for this request and is never stored or logged.
 * On invalid credentials `AuthApi.Login` rejects with the typed `ApiError`
 * (HTTP 401); that error is allowed to propagate so the `/signon` page can
 * display it. The returned {@link CurrentUser} lets the caller branch on role
 * immediately (admin -> `/admin`, regular -> `/menu`).
 *
 * @param credentials The signon user id and password (grouped into one object).
 * @returns The resolved current user (id, role, optional names).
 */
export async function Login(credentials: LoginRequest): Promise<CurrentUser> {
    const loginResponse: LoginResponse = await AuthApi.Login(credentials);
    const currentUser: CurrentUser = {
        user_id: loginResponse.user_id,
        user_type: loginResponse.user_type,
        first_name: loginResponse.first_name,
        last_name: loginResponse.last_name,
    };
    StoreCurrentUser(currentUser);
    return currentUser;
}

/**
 * Logs the user out end-to-end and returns them to the signon screen — but ONLY
 * once the server confirms the session was revoked.
 *
 * The authoritative session is the HTTP-only `carddemo_session` cookie, which
 * JavaScript can neither read nor delete; clearing only the mirrored
 * `localStorage` identity would therefore leave the cookie valid, so the very
 * next authenticated request would still succeed (QA issue #17). This helper
 * asks the backend to invalidate the session via `AuthApi.Logout()`, which
 * responds with a cookie-deletion header so the browser drops the cookie;
 * afterwards protected calls carry no credential and return HTTP 401.
 *
 * SECURITY (QA Issue 2 / CWE-613): the previous implementation swallowed any
 * revocation failure and ALWAYS cleared local identity and redirected, so a
 * failed/unreachable logout was presented as success while the live server
 * session kept authorizing protected requests. Sign-out is now confirmed:
 *
 *   - Server revocation is attempted up to {@link LOGOUT_MAX_ATTEMPTS} times
 *     (spaced by {@link LOGOUT_RETRY_DELAY_MS}) to ride out a transient failure.
 *   - Local teardown ({@link ClearStoredAuth}) and the `/signon` redirect run
 *     ONLY after the server confirms invalidation.
 *   - If every attempt fails, the mirrored identity is deliberately LEFT INTACT
 *     (no redirect) and the failure is propagated to the caller so the UI can
 *     keep the user in an explicit logout-failed state and offer a retry, rather
 *     than falsely showing a signed-out state.
 *
 * Only the SPECIFIC, expected failure surface (a typed {@link ApiError} from the
 * round-trip) is retried; any other (unexpected) error is re-thrown immediately
 * rather than swallowed, per the Ochs error-handling rule.
 *
 * @returns A promise that resolves once the server has confirmed sign-out and the
 *   redirect has been issued.
 * @throws The last {@link ApiError} (or a wrapped {@link Error}) when server-side
 *   revocation could not be confirmed after the bounded retries.
 */
export async function Logout(): Promise<void> {
    let lastError: unknown = null;
    for (let attempt = 1; attempt <= LOGOUT_MAX_ATTEMPTS; attempt += 1) {
        try {
            // Ask the backend to invalidate the HTTP-only session cookie.
            await AuthApi.Logout();
            // CONFIRMED: only now is it safe to tear down local state and leave
            // the SPA. The mirrored identity is cleared (the same key the 401
            // interceptor clears) and a hard redirect to /signon is performed
            // (SSR-guarded).
            ClearStoredAuth();
            if (typeof window !== 'undefined') {
                window.location.href = '/signon';
            }
            return;
        } catch (error) {
            // Retry ONLY the expected API failure surface; a non-ApiError is
            // unexpected and must not be swallowed (Ochs specific-catch rule).
            if (!IsApiError(error)) {
                throw error;
            }
            lastError = error;
            if (attempt < LOGOUT_MAX_ATTEMPTS) {
                await Delay(LOGOUT_RETRY_DELAY_MS);
            }
        }
    }
    // Every attempt failed: the server session may still be live, so DO NOT clear
    // local identity or redirect. Propagate so the caller surfaces an explicit
    // logout-failed state and can retry (QA Issue 2 / CWE-613).
    if (lastError instanceof Error) {
        throw lastError;
    }
    throw new Error(LOGOUT_FAILED_MESSAGE);
}

/**
 * Returns the currently signed-in principal from mirrored client state, or
 * `null` when no one is signed in (or during SSR). This is the stateless
 * stand-in for reading CDEMO-USER-ID / CDEMO-USER-TYPE out of the legacy
 * COMMAREA.
 *
 * @returns The current user, or `null` when unavailable.
 */
export function GetCurrentUser(): CurrentUser | null {
    return ReadStoredUser();
}

/**
 * Returns the current user's role -- `'A'` (admin) or `'U'` (regular) -- or
 * `null` when no one is signed in. Mirrors the legacy CDEMO-USER-TYPE flag.
 *
 * @returns The role union value, or `null` when unavailable.
 */
export function GetRole(): 'A' | 'U' | null {
    const currentUser = GetCurrentUser();
    if (currentUser) {
        return currentUser.user_type;
    }
    return null;
}

/**
 * Reports whether the current user is an administrator (`user_type === 'A'`).
 * Drives client-side gating of the admin screens (COADM01 and COUSR00-03).
 *
 * UX ONLY: the server independently enforces admin access via its
 * `require_admin` dependency, so this is never the security boundary.
 *
 * @returns `true` only for admin users; `false` otherwise (including signed-out).
 */
export function IsAdmin(): boolean {
    return GetRole() === 'A';
}
