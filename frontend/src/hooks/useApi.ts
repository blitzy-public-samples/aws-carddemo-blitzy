/**
 * :module: useApi
 * :purpose: Small, generic async-request hook that wraps any CardDemo ``../api``
 *     call and centralizes its loading/error lifecycle so the 17 page components
 *     do not each re-implement it. It replaces the synchronous CICS screen
 *     round-trip with React state (``data`` / ``loading`` / ``error``) while
 *     preserving observable workflow behaviour: it is presentational plumbing
 *     only and does not alter outcomes (AAP 0.7.1).
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
 *     client, not here (AAP 0.7.5). Logic only — no UI, no I/O of its own, no
 *     ``import.meta`` — so it is safe to import from Jest (jsdom).
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api';

/**
 * :purpose: Fallback message used when a caught value is neither an ``ApiError``
 *     nor a standard ``Error`` carrying a message; keeps the surfaced error
 *     non-empty for the calling page.
 */
const GENERIC_ERROR_MESSAGE = 'Unexpected error';

/**
 * :purpose: Return shape of :func:`useApi`, exposing the request state together
 *     with the trigger and reset callbacks the pages bind to.
 * :field data: the resolved payload of the most recent successful call, or
 *     ``null`` before the first success (and unchanged when a later call
 *     fails). Never transformed — the wire value is passed through verbatim.
 * :field loading: ``true`` while a call is in flight, ``false`` otherwise.
 * :field error: the normalized ``ApiError`` from the most recent failed call,
 *     or ``null`` when the last call succeeded or none has run. Typed
 *     ``ApiError`` (never ``any``/``unknown``) because the client guarantees it.
 * :field isOptimisticLockConflict: convenience flag, ``true`` only when the
 *     current ``error`` is the HTTP ``409`` optimistic-lock conflict
 *     (``error?.isOptimisticLockConflict``); drives the account-update conflict
 *     banner without the page re-checking the status.
 * :field run: trigger the wrapped call with its arguments; resolves to the
 *     payload on success or ``undefined`` on failure (the failure is reflected
 *     in ``error``). Never rejects — callers branch on ``error`` / the return
 *     value rather than catching.
 * :field reset: clear ``data``, ``error`` and ``loading`` back to their initial
 *     values.
 */
export interface UseApiResult<T, A extends unknown[]> {
  data: T | null;
  loading: boolean;
  error: ApiError | null;
  isOptimisticLockConflict: boolean;
  run: (...args: A) => Promise<T | undefined>;
  reset: () => void;
}

/**
 * :purpose: Wrap a single ``../api`` function in managed ``{ data, loading,
 *     error }`` state, exposing a trigger-driven ``run`` and a ``reset``. It is
 *     deliberately trigger-driven (it does NOT auto-run on mount) so the same
 *     hook serves GET (view/list) pages — which call ``run`` from their own
 *     ``useEffect`` — and mutation (update/add/pay) pages alike.
 * :param apiFn: the async api call to manage; invoked as ``apiFn(...args)`` by
 *     :func:`run`. Its resolved type ``T`` and argument tuple ``A`` flow through
 *     to the returned state, so ``data`` and ``run`` are fully typed with no
 *     casting at the call site.
 * :returns: a :class:`UseApiResult` for the wrapped call.
 * :note: State updates after an ``await`` are guarded by a mounted ref so a call
 *     that resolves after the component unmounts neither warns nor leaks. A
 *     caught value that is not already an ``ApiError`` (a programming or
 *     non-axios error that bypassed the client interceptor) is normalized to an
 *     ``ApiError`` with ``status`` ``0`` so the public ``error`` shape stays
 *     uniform.
 */
export function useApi<T, A extends unknown[] = []>(
  apiFn: (...args: A) => Promise<T>,
): UseApiResult<T, A> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Tracks whether the component is still mounted so we never call a state
  // setter after unmount. Set true on (re)mount and false on cleanup — writing
  // true in the effect body (not just the initial ref value) keeps the flag
  // correct under React StrictMode's dev mount/unmount/remount double-invoke.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const run = useCallback(
    async (...args: A): Promise<T | undefined> => {
      if (mountedRef.current) {
        setLoading(true);
        setError(null);
      }
      try {
        const result = await apiFn(...args);
        if (mountedRef.current) {
          setData(result);
        }
        return result;
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
        if (mountedRef.current) {
          setError(apiError);
        }
        return undefined;
      } finally {
        if (mountedRef.current) {
          setLoading(false);
        }
      }
    },
    [apiFn],
  );

  const reset = useCallback(() => {
    setData(null);
    setError(null);
    setLoading(false);
  }, []);

  const isOptimisticLockConflict = error?.isOptimisticLockConflict ?? false;

  return { data, loading, error, isOptimisticLockConflict, run, reset };
}
