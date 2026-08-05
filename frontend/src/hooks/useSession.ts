/**
 * :module: ``frontend/src/hooks/useSession.ts``
 * :purpose: Single source of truth for the authenticated user and role across
 *     the CardDemo single-page application. Re-expresses the CICS
 *     pseudo-conversational COMMAREA session / role field ``CDEMO-USER-TYPE``
 *     (``'A'`` administrator / ``'U'`` standard user) and the ``COSGN00C``
 *     sign-on routing as an externalized cookie session, surfaced to the route
 *     guards and menu gating through the :func:`useSession` hook.
 * :output: The named ``useSession`` hook and its :ts:type:`UseSessionResult`
 *     return contract.
 * :note: The SERVER is the authority. Identity and role are published only by
 *     ``POST /auth/signon`` and by the ``GET /session`` probe that reads the
 *     server-held session, and sign-out is not complete until
 *     ``POST /logout`` has revoked that session. Browser storage holds no
 *     authority: the store is in memory only, so editing storage cannot grant a
 *     role.
 * :note: Provider-free — state lives in a module-level observable store consumed
 *     via React ``useSyncExternalStore``, so every caller shares one store with
 *     no surrounding React Context provider. Logic only; no UI, no design
 *     system. All browser globals are guarded and no build-time environment is
 *     read here, so the module imports cleanly under Jest (jsdom).
 */

import { useEffect, useSyncExternalStore } from 'react';
import type {
  Role,
  SessionContext,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from '../types';
import {
  clearLocalCredentials,
  getSessionIdentity,
  logout,
  registerSessionExpiryHandler,
  signon,
} from '../api';

/**
 * :purpose: First-entry program-context value, mirroring COMMAREA
 *     ``88 CDEMO-PGM-ENTER VALUE 0``; assigned to a freshly established session
 *     (``COSGN00C`` moves zeros to ``CDEMO-PGM-CONTEXT`` on sign-on).
 */
const PGM_CONTEXT_ENTER = 0;

/**
 * :purpose: Module-private session state backing the store.
 * :field user: authenticated user id (``CDEMO-USER-ID``); ``null`` when signed
 *     out.
 * :field role: user role (``CDEMO-USER-TYPE`` — ``'A'`` / ``'U'``); ``null``
 *     when signed out.
 * :field session: full externalized session context when available; ``null``
 *     when signed out.
 */
interface SessionState {
  user: string | null;
  role: Role | null;
  session: SessionContext | null;
  resolved: boolean;
}

/**
 * :purpose: Canonical pre-probe state and the stable server snapshot: signed out and
 *     NOT yet resolved, so a route guard waits for the server answer instead of
 *     redirecting a deep link that carries a valid session cookie. A single shared
 *     reference so ``useSyncExternalStore`` never observes a changing identity.
 */
const EMPTY_STATE: SessionState = {
  user: null,
  role: null,
  session: null,
  resolved: false,
};

/**
 * :purpose: Signed-out state the SERVER has confirmed — the identity probe answered
 *     "no session", the session was revoked through ``POST /logout``, or a request
 *     reported it gone. Distinct from :data:`EMPTY_STATE` so a route guard can tell
 *     "not asked yet" from "asked, and there is no session".
 */
const SIGNED_OUT_STATE: SessionState = {
  user: null,
  role: null,
  session: null,
  resolved: true,
};

/**
 * :purpose: Narrow an unknown value to a valid :ts:type:`Role`.
 * :param value: the candidate value.
 * :returns: ``true`` when ``value`` is the verbatim administrator (``'A'``) or
 *     standard-user (``'U'``) role code.
 */
function isValidRole(value: unknown): value is Role {
  return value === CDEMO_USRTYP_ADMIN || value === CDEMO_USRTYP_USER;
}

/**
 * :purpose: Build the in-memory state for a server-published identity.
 * :param user: the user id the server reported (``CDEMO-USER-ID``).
 * :param role: the role the server reported (``CDEMO-USER-TYPE``).
 * :returns: the populated :ts:type:`SessionState`.
 */
function stateFor(user: string, role: Role): SessionState {
  const session: SessionContext = {
    userId: user,
    userType: role,
    programContext: PGM_CONTEXT_ENTER,
  };
  return { user, role, session, resolved: true };
}

/**
 * :purpose: Current store state; a single object reference that only
 *     :func:`setState` replaces, so :func:`getSnapshot` stays cached. It starts
 *     signed out on every load: the server, not the browser, republishes the
 *     identity through :func:`refresh`.
 */
let currentState: SessionState = EMPTY_STATE;

/**
 * :purpose: Registered store subscribers, notified on every state change.
 */
const listeners = new Set<() => void>();

/**
 * :purpose: ``useSyncExternalStore`` client snapshot.
 * :returns: the current state reference, stable until :func:`setState` replaces
 *     it.
 */
function getSnapshot(): SessionState {
  return currentState;
}

/**
 * :purpose: ``useSyncExternalStore`` server snapshot.
 * :returns: the stable :data:`EMPTY_STATE`, avoiding a hydration mismatch;
 *     storage is never read here.
 */
function getServerSnapshot(): SessionState {
  return EMPTY_STATE;
}

/**
 * :purpose: Subscribe a listener to store changes.
 * :param listener: callback invoked after each state change.
 * :returns: an unsubscribe function that removes the listener.
 */
function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * :purpose: Replace the store state, persist it, and notify subscribers.
 * :param next: the new state; always a fresh object so the snapshot identity
 *     changes exactly once per update.
 */
function setState(next: SessionState): void {
  currentState = next;
  listeners.forEach((listener) => listener());
}

/**
 * :purpose: Authenticate a user against ``POST /auth/signon`` (legacy
 *     ``COSGN00C`` / transaction ``CC00``) and establish the session on success.
 * :param userId: the user id, sent verbatim — never upper-cased, transformed,
 *     or logged.
 * :param password: the password, sent verbatim — never upper-cased, stored, or
 *     logged.
 * :returns: a promise resolving to the :ts:type:`SignonResponseDto`; a rejected
 *     ``signon`` (for example a ``401`` wrong-password ``ApiError``) propagates
 *     unchanged to the caller and leaves the session signed out.
 */
async function signIn(
  userId: string,
  password: string,
): Promise<SignonResponseDto> {
  // The legacy sign-on screen was only ever reached by ending the current session
  // (``RETURN-TO-SIGNON-SCREEN``), so a sign-on never arrived over a live one. The
  // server is asked first whether one is live, and any live session is revoked
  // before the credentials are sent, which keeps the same one-session-at-a-time
  // sequence and leaves exactly one participant replacing the session id.
  await probeIdentityOnce();
  if (currentState.user !== null) {
    await signOut();
  }
  const request: SignonRequestDto = { userId, password };
  const response = await signon(request);
  setState(stateFor(response.userId, response.userType));
  return response;
}

/**
 * :purpose: Sign out. The SERVER-side session is revoked first through the
 *     CSRF-protected ``POST /logout``, and only then is the local session, the
 *     optional bearer token and every cached response cleared, so a signed-out
 *     screen is never presented over a session cookie that is still valid.
 * :returns: a promise that resolves once the session has been revoked and the
 *     local state cleared.
 * :raises ApiError: when the revocation call fails; the local session is left in
 *     place so the caller can report the failure instead of pretending the
 *     session is gone.
 */
async function signOut(): Promise<void> {
  await logout();
  clearLocalCredentials();
  identityProbe = null;
  setState(SIGNED_OUT_STATE);
}

/**
 * :purpose: Drop the local authority without a server round trip, for the case
 *     where the server has already told us the session is gone (a ``401`` or
 *     ``403`` on any request).
 */
function abandonSession(): void {
  clearLocalCredentials();
  identityProbe = null;
  if (currentState !== SIGNED_OUT_STATE) {
    setState(SIGNED_OUT_STATE);
  }
}

// Any rejected request republishes the signed-out state exactly once, so an
// expired or revoked session cannot leave a stale role driving the UI.
registerSessionExpiryHandler(abandonSession);

/**
 * :purpose: Re-resolve the signed-on identity from the server-held session through
 *     ``GET /session``, so a reload or a deep link recovers the identity and role
 *     from the authority that owns them.
 * :returns: a promise resolving to ``true`` when the server published an identity
 *     and ``false`` when it reported no usable session.
 */
async function refresh(): Promise<boolean> {
  try {
    const identity = await getSessionIdentity();
    if (typeof identity.userId !== 'string' || !isValidRole(identity.userType)) {
      setState(SIGNED_OUT_STATE);
      return false;
    }
    setState(stateFor(identity.userId, identity.userType));
    return true;
  } catch {
    setState(SIGNED_OUT_STATE);
    return false;
  }
}

/**
 * :purpose: Memo of the in-flight or completed server identity probe, so the probe is
 *     issued once however many components mount and every caller awaits the same
 *     round trip. Cleared whenever the session ends, so the next mount re-asks the
 *     server rather than trusting a stale conclusion.
 */
let identityProbe: Promise<void> | null = null;

/**
 * :purpose: Issue the server identity probe, at most once per session lifetime.
 * :returns: a promise that settles once the probe has completed.
 */
function probeIdentityOnce(): Promise<void> {
  identityProbe ??= refresh().then(() => undefined);
  return identityProbe;
}

/**
 * :purpose: Return contract of :func:`useSession`.
 * :field user: authenticated user id, or ``null`` when signed out.
 * :field role: user role (``'A'`` / ``'U'``), or ``null`` when signed out.
 * :field session: externalized session context, or ``null`` when signed out.
 * :field isAuthenticated: ``true`` when both ``user`` and ``role`` are present.
 * :field isSessionResolved: ``true`` once the server has answered the identity probe
 *     (or an explicit sign-in / sign-out has settled it). ``false`` only in the
 *     window before that answer, during which a route guard must wait rather than
 *     treat the caller as signed out.
 * :field isAdmin: ``true`` only for the administrator role (``'A'``).
 * :field signIn: authenticate and establish the session.
 * :field signOut: revoke the server session, then clear the local session.
 * :field refresh: re-resolve the identity from the server-held session.
 */
export interface UseSessionResult {
  user: string | null;
  role: Role | null;
  session: SessionContext | null;
  isAuthenticated: boolean;
  isSessionResolved: boolean;
  isAdmin: boolean;
  signIn: (
    userId: string,
    password: string,
  ) => Promise<SessionContext | SignonResponseDto>;
  signOut: () => Promise<void>;
  refresh: () => Promise<boolean>;
}

/**
 * :purpose: Subscribe to the module-level session store and expose the current
 *     user, role, derived flags, and the sign-in / sign-out actions. Works with
 *     no surrounding Context provider — every caller shares one store.
 * :returns: the :ts:type:`UseSessionResult` for the current session.
 */
export function useSession(): UseSessionResult {
  const state = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  // One server probe per page load republishes the identity the server holds, so a
  // reload or a deep link recovers it from the authority rather than from storage.
  useEffect(() => {
    void probeIdentityOnce();
  }, []);
  const isAuthenticated = state.user !== null && state.role !== null;
  const isAdmin = state.role === CDEMO_USRTYP_ADMIN;
  return {
    user: state.user,
    role: state.role,
    session: state.session,
    isAuthenticated,
    isSessionResolved: state.resolved,
    isAdmin,
    signIn,
    signOut,
    refresh,
  };
}
