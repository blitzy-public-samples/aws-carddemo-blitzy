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
import type { SessionRejectionReason } from '../api';
import { SESSION_ENDED_MESSAGE } from '../api/messages';
import {
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
 * :field resolved: whether the server has answered the identity question at all.
 * :field notice: text the sign-on screen must show because the session ended
 *     without the operator asking; ``null`` whenever there is nothing to report.
 */
interface SessionState {
  user: string | null;
  role: Role | null;
  session: SessionContext | null;
  resolved: boolean;
  notice: string | null;
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
  notice: null,
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
  notice: null,
};

/*
 * The text the sign-on screen reports when the server-held session ended without the
 * operator ending it, so an expiry is never silent. Re-exported rather than declared:
 * the literal, and the note on why it has no legacy analogue, live with the other
 * client-side line-23 texts in `api/messages.ts`, so the guards, the response
 * interceptor and this store all report the condition in one wording.
 */
export { SESSION_ENDED_MESSAGE };

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
  // A published identity leaves nothing to report: the operator is signed on.
  return { user, role, session, resolved: true, notice: null };
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
 * :purpose: Build the SERVER-CONFIRMED signed-out state, carrying forward the notice
 *     already published unless the caller supplies one. The notice has to survive this
 *     transition: the identity probe that follows an expiry answers "no session" and
 *     would otherwise erase the very message the expiry raised.
 * :param notice: text to publish, or ``null`` to keep whatever is already published.
 * :returns: the signed-out :ts:type:`SessionState`; the shared
 *     :data:`SIGNED_OUT_STATE` reference itself when there is nothing to report.
 */
function signedOutState(notice: string | null = null): SessionState {
  const carried = notice ?? currentState.notice;
  return carried === null
    ? SIGNED_OUT_STATE
    : { ...SIGNED_OUT_STATE, notice: carried };
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
 *     unchanged to the caller and leaves ANY session already held exactly as it was.
 * :note: Nothing is revoked before the answer is known. ``COSGN00C`` decides what a
 *     sign-on costs the terminal by reading the credential store and comparing the
 *     password -- a refusal publishes a literal on line 23 and the operator keeps the
 *     screen they were on. Ending the current session first would make a mistyped
 *     password destroy a session the operator is still signed on to, and it would do so
 *     silently, since the only thing the screen then reports is the refusal. The
 *     take-over of a live session belongs to the service that verified the credential,
 *     which reuses the session record and re-indexes the principal in one place.
 */
async function signIn(
  userId: string,
  password: string,
): Promise<SignonResponseDto> {
  const request: SignonRequestDto = { userId, password };
  const response = await signon(request);
  setState(stateFor(response.userId, response.userType));
  // The identity is now known from the answer to the sign-on itself, which is the same
  // question the probe asks, so the probe is marked SETTLED rather than cleared. Clearing
  // it would send the next mount back to the server to re-ask what this response just
  // established, and would let that later answer overwrite the state set here.
  identityProbe = Promise.resolve();
  return response;
}

/**
 * :purpose: Sign out. The SERVER-side session is revoked first through the
 *     CSRF-protected ``POST /logout``, and only then is the local session cleared, so
 *     a signed-out screen is never presented over a session cookie that is still
 *     valid.
 * :returns: a promise that resolves once the session has been revoked and the
 *     local state cleared.
 * :raises ApiError: when the revocation call fails; the local session is left in
 *     place so the caller can report the failure instead of pretending the
 *     session is gone.
 */
async function signOut(): Promise<void> {
  await logout();
  identityProbe = null;
  // An operator-ended session is not an expiry, so nothing is reported on the way out.
  setState(SIGNED_OUT_STATE);
}

/**
 * :purpose: Drop the local authority without a server round trip, for the case
 *     where the server has already told us the session is gone (a ``401`` or
 *     ``403`` on any request). Dropping it is also what moves the operator: the route
 *     guard sees a resolved, signed-out session and navigates to the sign-on screen
 *     client-side, so the application is never re-downloaded to report an expiry.
 * :param reason: ``'expired'`` publishes :data:`SESSION_ENDED_MESSAGE` so the sign-on
 *     screen tells the operator why they are back there; ``'refused'`` publishes
 *     nothing, because a refusal is answered by the program that refused it — the
 *     legacy literal ``No access - Admin Only option... `` is `MenuController`'s to
 *     send, not the client's to invent.
 */
function abandonSession(reason: SessionRejectionReason): void {
  identityProbe = null;
  const next = signedOutState(reason === 'expired' ? SESSION_ENDED_MESSAGE : null);
  if (currentState !== next) {
    setState(next);
  }
}

/**
 * :purpose: Withdraw the published notice once it has been read, so it is reported for
 *     the expiry that raised it and not for the next screen the operator reaches.
 */
function clearSessionNotice(): void {
  if (currentState.notice !== null) {
    setState({ ...currentState, notice: null });
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
 * :note: A probe that finds no session CONFIRMS the signed-out state without erasing a
 *     notice already published — the probe an expiry triggers must not silence the
 *     expiry it is reporting. A published identity clears the notice, because the
 *     operator is signed on again and there is nothing left to report.
 */
async function refresh(): Promise<boolean> {
  try {
    const identity = await getSessionIdentity();
    if (typeof identity.userId !== 'string' || !isValidRole(identity.userType)) {
      setState(signedOutState());
      return false;
    }
    setState(stateFor(identity.userId, identity.userType));
    return true;
  } catch {
    setState(signedOutState());
    return false;
  }
}

/**
 * :purpose: Discard the conclusion of the last identity probe and ask the server
 *     again, for a document that was RESTORED rather than loaded: a back-forward-cache
 *     restore reinstates the rendered screen and issues no request of its own, so the
 *     identity it was drawn under has to be re-established.
 * :returns: a promise resolving to ``true`` when the server still publishes an
 *     identity and ``false`` when it reports no usable session.
 * :note: The store is returned to :data:`EMPTY_STATE` FIRST. That is the not-yet-asked
 *     state, so every route guard renders its waiting announcement and the restored
 *     protected screen is unmounted for the whole round trip rather than staying on
 *     display until the answer arrives.
 */
async function revalidate(): Promise<boolean> {
  identityProbe = null;
  if (currentState !== EMPTY_STATE) {
    setState(EMPTY_STATE);
  }
  return refresh();
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
 * :field sessionNotice: text the sign-on screen must report because the session ended
 *     without the operator ending it (an expired cookie session), or ``null`` when
 *     there is nothing to report. It survives the identity probe that follows the
 *     expiry and is withdrawn by :func:`clearSessionNotice`.
 * :field clearSessionNotice: withdraw the published notice once it has been reported.
 * :field signIn: authenticate and establish the session.
 * :field signOut: revoke the server session, then clear the local session.
 * :field refresh: re-resolve the identity from the server-held session.
 * :field revalidate: discard the last conclusion and re-ask the server, clearing the
 *     published identity for the duration so a restored screen is not left on display
 *     under an identity that may already be gone.
 */
export interface UseSessionResult {
  user: string | null;
  role: Role | null;
  session: SessionContext | null;
  isAuthenticated: boolean;
  isSessionResolved: boolean;
  isAdmin: boolean;
  sessionNotice: string | null;
  clearSessionNotice: () => void;
  signIn: (
    userId: string,
    password: string,
  ) => Promise<SessionContext | SignonResponseDto>;
  signOut: () => Promise<void>;
  refresh: () => Promise<boolean>;
  revalidate: () => Promise<boolean>;
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
    sessionNotice: state.notice,
    clearSessionNotice,
    signIn,
    signOut,
    refresh,
    revalidate,
  };
}
