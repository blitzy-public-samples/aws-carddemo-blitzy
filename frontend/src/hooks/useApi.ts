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
 *     superseded call's ``AbortSignal`` is aborted. ``signal`` is published on the
 *     result so a caller can thread it into a request that accepts one.
 * :note: Held data is dropped rather than left on screen whenever it would no
 *     longer belong to what is displayed: when the wrapped call itself changes
 *     (a new target), when a call fails, and when the signed-on identity changes.
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
import { ApiError } from '../api';
import { useSession } from './useSession';

/**
 * :purpose: Fallback message used when a caught value is neither an ``ApiError``
 *     nor a standard ``Error`` carrying a message; keeps the surfaced error
 *     non-empty for the calling page.
 */
const GENERIC_ERROR_MESSAGE = 'Unexpected error';

/**
 * :purpose: Return shape of :func:`useApi`, exposing the request state together
 *     with the trigger and reset callbacks the pages bind to.
 * :field data: the resolved payload of the newest successful call, or ``null``
 *     before the first success and after a failure, a target change or an
 *     identity change. Never transformed — the wire value is passed through
 *     verbatim.
 * :field loading: ``true`` while the newest call is in flight, ``false``
 *     otherwise.
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

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      controllerRef.current?.abort();
    };
  }, []);

  const reset = useCallback(() => {
    generationRef.current += 1;
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
      // abort lets a request that accepts the signal stop work it no longer needs.
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;

      /**
       * :purpose: Whether this call may still write state.
       * :returns: ``true`` while the component is mounted and no later ``run`` or
       *     ``reset`` has superseded this generation.
       */
      const current = (): boolean =>
        mountedRef.current && generationRef.current === generation;

      if (current()) {
        setLoading(true);
        setError(null);
        setSignal(controller.signal);
      }
      try {
        const result = await apiFn(...args);
        if (current()) {
          setData(result);
          setLoading(false);
        }
        return current() ? result : undefined;
      } catch (err) {
        // The client interceptor normalizes every HTTP/network failure to an
        // ApiError; anything else is normalized here to status 0. We store it
        // and return undefined — we never rethrow, redirect, or retry (a retry
        // would mask the 409 optimistic-lock conflict — AAP 0.6.2).
        const apiError =
          err instanceof ApiError
            ? err
            : new ApiError(
                0,
                err instanceof Error ? err.message : GENERIC_ERROR_MESSAGE,
              );
        if (current()) {
          // The held payload described the call that just failed, so it is dropped
          // rather than left on screen beside the failure message.
          setData(null);
          setError(apiError);
          setLoading(false);
        }
        return undefined;
      }
    },
    [apiFn],
  );

  const isOptimisticLockConflict = error?.isOptimisticLockConflict ?? false;

  return { data, loading, error, isOptimisticLockConflict, signal, run, reset };
}
