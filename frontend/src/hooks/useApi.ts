/**
 * :module: useApi
 * :purpose: Small, generic async-request hook that wraps any CardDemo ``../api``
 *     call and centralizes its loading/error lifecycle so the 17 page components
 *     do not each re-implement it. It replaces the synchronous CICS screen
 *     round-trip with React state (``data`` / ``loading`` / ``error``) while
 *     preserving observable workflow behaviour: it is presentational plumbing
 *     only and does not alter outcomes (AAP 0.7.1).
 * :output: The named ``useApi`` hook and its :ts:type:`UseApiResult` contract.
 * :note: Calls are generation-numbered. Every ``run`` supersedes the calls before
 *     it: only the newest generation may write ``data``, ``error`` or ``loading``,
 *     so a slow earlier response can never overwrite a newer one, and the
 *     superseded call's ``AbortSignal`` is aborted. The abort REACHES the transport:
 *     ``run`` issues the wrapped call inside ``runWithRequestSignal``, so axios
 *     carries the signal on the request and a superseded or abandoned call is
 *     genuinely cancelled rather than left for the server to service. ``signal`` is
 *     also published on the result for a caller that threads it somewhere else.
 * :note: Held data survives a FAILED call. A transient failure (offline, a timeout,
 *     a rejected browse) leaves the rows and the page cursor the operator was looking
 *     at on screen with the message beside them, exactly as a CICS program re-sent
 *     the map it had already built and moved its text into ``ERRMSG``; it is dropped
 *     only when it would belong to something else — when the wrapped call itself
 *     changes (a new target) or when the signed-on identity changes.
 * :note: This hook faithfully SURFACES — and never masks — the outcomes the
 *     ``apiClient`` response interceptor has already normalized into an
 *     ``ApiError``. In particular the HTTP ``409`` optimistic-lock conflict
 *     (legacy ``COACTUPC`` read-snapshot-compare-rewrite, AAP 0.6.2) is exposed
 *     through ``error.isOptimisticLockConflict`` and the derived
 *     ``isOptimisticLockConflict`` flag, and the HTTP ``401`` session-expired
 *     result is stored as-is. The hook performs NO redirect, NO retry (a retry
 *     would mask a ``409`` conflict), and NO transform of the wire payload
 *     (money stays a ``string``, dates stay ``YYYY-MM-DD``). The ``401`` ->
 *     ``/signon`` redirect and correlation-id/tracing concerns live in the
 *     client, not here (AAP 0.7.5).
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isCancelledRequest, runWithRequestSignal } from '../api';
import { GENERIC_ERROR_MESSAGE } from '../api/messages';
import { useSession } from './useSession';

/**
 * :purpose: Return shape of :func:`useApi`, exposing the request state together
 *     with the trigger and reset callbacks the pages bind to.
 * :field data: the resolved payload of the newest SUCCESSFUL call, or ``null``
 *     before the first success and after a target change or an identity change. A
 *     failure leaves it in place so the screen keeps what it was showing. Never
 *     transformed — the wire value is passed through verbatim.
 * :field loading: ``true`` while the newest call is in flight, ``false``
 *     otherwise. It is RENDER state, so it is observed one render behind an event
 *     handler that has just called :func:`run`; a handler that must decide within
 *     the same tick asks :func:`isInFlight` instead.
 * :field isInFlight: whether a call is in flight, answered SYNCHRONOUSLY. It turns
 *     true inside ``run`` before the request is issued and false when that call
 *     settles, so several handlers invoked in one tick all see the first one's
 *     effect. It is the 3270 keyboard lock: a screen consults it to inhibit an
 *     attention identifier that arrives while the terminal is waiting for the
 *     server, rather than issuing a second, identical request.
 * :field error: the normalized ``ApiError`` from the newest failed call, or
 *     ``null`` when the newest call succeeded or none has run. Typed
 *     ``ApiError`` (never ``any``/``unknown``) because the client guarantees it.
 * :field isOptimisticLockConflict: convenience flag, ``true`` only when the
 *     current ``error`` is the HTTP ``409`` optimistic-lock conflict
 *     (``error?.isOptimisticLockConflict``); drives the account-update conflict
 *     banner without the page re-checking the status.
 * :field signal: the ``AbortSignal`` of the newest call, aborted when a later
 *     ``run`` supersedes it, when :func:`reset` runs, or on unmount; ``null``
 *     before the first call.
 * :field run: trigger the wrapped call with its arguments; resolves to the
 *     payload on success or ``undefined`` on failure or when superseded (the
 *     failure is reflected in ``error``). Never rejects — callers branch on
 *     ``error`` / the return value rather than catching.
 * :field reset: abort the call in flight and clear ``data``, ``error`` and
 *     ``loading`` back to their initial values.
 */
export interface UseApiResult<T, A extends unknown[]> {
  data: T | null;
  loading: boolean;
  isInFlight: () => boolean;
  error: ApiError | null;
  isOptimisticLockConflict: boolean;
  signal: AbortSignal | null;
  run: (...args: A) => Promise<T | undefined>;
  reset: () => void;
}

/**
 * :purpose: Wrap a single ``../api`` function in managed ``{ data, loading,
 *     error }`` state, exposing a trigger-driven ``run`` and a ``reset``. It does
 *     not auto-run on mount: a read page calls ``run`` from its own ``useEffect``
 *     and a mutation page calls it from its handler.
 * :param apiFn: the async api call to manage; invoked as ``apiFn(...args)`` by
 *     :func:`run`. Its resolved type ``T`` and argument tuple ``A`` flow through
 *     to the returned state, so ``data`` and ``run`` are fully typed with no
 *     casting at the call site.
 * :returns: a :class:`UseApiResult` for the wrapped call.
 * :note: A caught value that is not already an ``ApiError`` (a programming or
 *     non-axios error that bypassed the client interceptor) is normalized to an
 *     ``ApiError`` with ``status`` ``0`` so the public ``error`` shape stays
 *     uniform. A ``run`` whose generation has been superseded, or whose component
 *     has unmounted, writes no state at all.
 */
export function useApi<T, A extends unknown[] = []>(
  apiFn: (...args: A) => Promise<T>,
): UseApiResult<T, A> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [signal, setSignal] = useState<AbortSignal | null>(null);
  const { user } = useSession();

  // Tracks whether the component is still mounted so we never call a state
  // setter after unmount. Set true on (re)mount and false on cleanup — writing
  // true in the effect body (not just the initial ref value) keeps the flag
  // correct under React StrictMode's dev mount/unmount/remount double-invoke.
  const mountedRef = useRef(true);
  // Monotonic call counter. `run` captures its own generation and writes state
  // only while it is still the newest, so an out-of-order response is dropped.
  const generationRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  // Synchronous mirror of `loading`, so a handler can tell within its own tick that
  // a call is already outstanding. `loading` cannot answer that: it is render state.
  const inFlightRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      inFlightRef.current = false;
      controllerRef.current?.abort();
    };
  }, []);

  /**
   * :purpose: Whether a call is outstanding, answered without waiting for a render.
   * :returns: ``true`` between the start of a ``run`` and that call settling.
   */
  const isInFlight = useCallback((): boolean => inFlightRef.current, []);

  const reset = useCallback(() => {
    generationRef.current += 1;
    inFlightRef.current = false;
    controllerRef.current?.abort();
    controllerRef.current = null;
    setData(null);
    setError(null);
    setLoading(false);
    setSignal(null);
  }, []);

  // The wrapped call identifies the target being read, so a change of target must
  // not leave the previous target's payload on screen; the same applies when the
  // signed-on identity changes, so one user's data is never shown to the next.
  const clearedForRef = useRef<{ apiFn: unknown; user: string | null }>({
    apiFn,
    user,
  });
  useEffect(() => {
    const previous = clearedForRef.current;
    if (previous.apiFn === apiFn && previous.user === user) {
      return;
    }
    clearedForRef.current = { apiFn, user };
    reset();
  }, [apiFn, user, reset]);

  const run = useCallback(
    async (...args: A): Promise<T | undefined> => {
      generationRef.current += 1;
      const generation = generationRef.current;
      // Supersede the call in flight: its result is already ignored below, and the
      // abort now reaches axios through `runWithRequestSignal`, so the superseded
      // request is cancelled instead of being serviced for nobody.
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;
      inFlightRef.current = true;

      /**
       * :purpose: Whether this call may still write state.
       * :returns: ``true`` while the component is mounted and no later ``run`` or
       *     ``reset`` has superseded this generation.
       */
      const current = (): boolean =>
        mountedRef.current && generationRef.current === generation;

      /**
       * :purpose: Release the keyboard lock, but only when this call is still the
       *     newest one — a superseded call settling must not report the terminal
       *     free while its successor is still waiting for the server.
       */
      const releaseIfNewest = (): void => {
        if (generationRef.current === generation) {
          inFlightRef.current = false;
        }
      };

      if (current()) {
        setLoading(true);
        setError(null);
        setSignal(controller.signal);
      }
      try {
        // The signal travels with the request, so aborting the controller above
        // cancels the transport and not just this hook's interest in the answer.
        const result = await runWithRequestSignal(controller.signal, () =>
          apiFn(...args),
        );
        releaseIfNewest();
        if (current()) {
          setData(result);
          setLoading(false);
        }
        return current() ? result : undefined;
      } catch (err) {
        releaseIfNewest();
        // A cancellation is the caller having moved on, not a failure, so no error is
        // ever published for it. Normally a newer generation already owns the state
        // and nothing at all is written; when this call still owns it — an abort
        // through the published signal, with no successor — only the wait is cleared.
        if (isCancelledRequest(err)) {
          if (current()) {
            setLoading(false);
          }
          return undefined;
        }
        // The client interceptor normalizes every HTTP/network failure to an
        // ApiError; anything else is normalized here to status 0. We store it
        // and return undefined — we never rethrow, redirect, or retry (a retry
        // would mask the 409 optimistic-lock conflict — AAP 0.6.2).
        // A value that never went through the client interceptor carries a
        // library or programming diagnostic in its `message`, which line 23 must
        // not render, so the application's own generic stands in for it.
        const apiError =
          err instanceof ApiError ? err : new ApiError(0, GENERIC_ERROR_MESSAGE);
        if (current()) {
          // The payload already on screen is KEPT: a failed turn re-presents the map
          // the operator was reading with the message beside it, rather than clearing
          // the screen and regressing the page cursor with it.
          setError(apiError);
          setLoading(false);
        }
        return undefined;
      }
    },
    [apiFn],
  );

  const isOptimisticLockConflict = error?.isOptimisticLockConflict ?? false;

  return {
    data,
    loading,
    isInFlight,
    error,
    isOptimisticLockConflict,
    signal,
    run,
    reset,
  };
}
