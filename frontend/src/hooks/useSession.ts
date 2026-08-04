/**
 * :module: ``frontend/src/hooks/useSession.ts``
 * :purpose: Single source of truth for the authenticated user and role across
 *     the CardDemo single-page application. Re-expresses the CICS
 *     pseudo-conversational COMMAREA session / role field ``CDEMO-USER-TYPE``
 *     (``'A'`` administrator / ``'U'`` standard user) and the ``COSGN00C``
 *     sign-on routing as an externalized (cookie / JWT) session, surfaced to the
 *     route guards and menu gating through the :func:`useSession` hook.
 * :output: The named ``useSession`` hook and its :ts:type:`UseSessionResult`
 *     return contract.
 * :note: Provider-free — state lives in a module-level observable store consumed
 *     via React ``useSyncExternalStore``, so every caller shares one store with
 *     no surrounding React Context provider. Logic only; no UI, no design
 *     system. All browser globals are guarded and no build-time environment is
 *     read here, so the module imports cleanly under Jest (jsdom).
 */

import { useSyncExternalStore } from 'react';
import type {
  Role,
  SessionContext,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from '../types';
import { signon } from '../api';

/**
 * :purpose: ``sessionStorage`` key under which the externalized session is
 *     persisted for rehydration across page reloads. Never holds a password.
 */
const STORAGE_KEY = 'carddemo.session';

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
}

/**
 * :purpose: Canonical signed-out state and the stable server snapshot. A single
 *     shared reference so ``useSyncExternalStore`` never observes a changing
 *     identity while signed out.
 */
const EMPTY_STATE: SessionState = { user: null, role: null, session: null };

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
 * :purpose: Read the persisted session from ``sessionStorage`` on module load.
 * :returns: the rehydrated :ts:type:`SessionState`, or :data:`EMPTY_STATE` when
 *     no valid session is stored or the environment has no ``sessionStorage``.
 */
function rehydrate(): SessionState {
  if (typeof window === 'undefined' || typeof sessionStorage === 'undefined') {
    return EMPTY_STATE;
  }
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (raw === null) {
      return EMPTY_STATE;
    }
    const parsed: unknown = JSON.parse(raw);
    if (parsed === null || typeof parsed !== 'object') {
      return EMPTY_STATE;
    }
    const record = parsed as Record<string, unknown>;
    const user = record.user;
    const role = record.role;
    if (typeof user !== 'string' || !isValidRole(role)) {
      return EMPTY_STATE;
    }
    const session: SessionContext = {
      userId: user,
      userType: role,
      programContext: PGM_CONTEXT_ENTER,
    };
    return { user, role, session };
  } catch {
    return EMPTY_STATE;
  }
}

/**
 * :purpose: Persist (or clear) the session in ``sessionStorage``. Writes only
 *     the user id, role, and non-secret session fields; never a password.
 * :param state: the state to persist; a signed-out state removes the entry.
 */
function persist(state: SessionState): void {
  if (typeof window === 'undefined' || typeof sessionStorage === 'undefined') {
    return;
  }
  try {
    if (state.user !== null && state.role !== null) {
      const payload = {
        user: state.user,
        role: state.role,
        session: state.session,
      };
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // sessionStorage may be unavailable (private mode / quota exceeded); the
    // empty handler is intentional — persistence is best-effort and must never
    // break sign-in or sign-out.
  }
}

/**
 * :purpose: Current store state; a single object reference that only
 *     :func:`setState` replaces, so :func:`getSnapshot` stays cached.
 */
let currentState: SessionState = rehydrate();

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
  persist(next);
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
  const request: SignonRequestDto = { userId, password };
  const response = await signon(request);
  const session: SessionContext = {
    userId: response.userId,
    userType: response.userType,
    programContext: PGM_CONTEXT_ENTER,
  };
  setState({ user: response.userId, role: response.userType, session });
  return response;
}

/**
 * :purpose: Clear the local session, returning the store to the signed-out
 *     state and removing the persisted entry.
 * :returns: a promise that resolves once local sign-out is complete.
 */
function signOut(): Promise<void> {
  setState(EMPTY_STATE);
  return Promise.resolve();
}

/**
 * :purpose: Seed the module-level store directly with a user id and role, without
 *     a network round trip, so a test can place the SPA in an authenticated or
 *     signed-out state. Passing ``null`` for both arguments restores the
 *     signed-out state.
 * :param user: the user id to publish (``CDEMO-USER-ID``), or ``null``.
 * :param role: the role to publish (``CDEMO-USER-TYPE`` — ``'A'`` / ``'U'``), or
 *     ``null``.
 * :note: Test seam only; never called by application code. It notifies
 *     ``useSyncExternalStore`` subscribers, so callers must wrap it in ``act``.
 */
export function __setSession(user: string | null, role: Role | null): void {
  if (user === null || role === null) {
    setState(EMPTY_STATE);
    return;
  }
  const session: SessionContext = {
    userId: user,
    userType: role,
    programContext: PGM_CONTEXT_ENTER,
  };
  setState({ user, role, session });
}

/**
 * :purpose: Return contract of :func:`useSession`.
 * :field user: authenticated user id, or ``null`` when signed out.
 * :field role: user role (``'A'`` / ``'U'``), or ``null`` when signed out.
 * :field session: externalized session context, or ``null`` when signed out.
 * :field isAuthenticated: ``true`` when both ``user`` and ``role`` are present.
 * :field isAdmin: ``true`` only for the administrator role (``'A'``).
 * :field signIn: authenticate and establish the session.
 * :field signOut: clear the local session.
 */
export interface UseSessionResult {
  user: string | null;
  role: Role | null;
  session: SessionContext | null;
  isAuthenticated: boolean;
  isAdmin: boolean;
  signIn: (
    userId: string,
    password: string,
  ) => Promise<SessionContext | SignonResponseDto>;
  signOut: () => Promise<void>;
}

/**
 * :purpose: Subscribe to the module-level session store and expose the current
 *     user, role, derived flags, and the sign-in / sign-out actions. Works with
 *     no surrounding Context provider — every caller shares one store.
 * :returns: the :ts:type:`UseSessionResult` for the current session.
 */
export function useSession(): UseSessionResult {
  const state = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  const isAuthenticated = state.user !== null && state.role !== null;
  const isAdmin = state.role === CDEMO_USRTYP_ADMIN;
  return {
    user: state.user,
    role: state.role,
    session: state.session,
    isAuthenticated,
    isAdmin,
    signIn,
    signOut,
  };
}
