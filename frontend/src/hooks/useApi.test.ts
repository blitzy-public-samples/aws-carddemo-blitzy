/**
 * :module: useApi.test
 * :purpose: Verify the generic async-request hook: the initial idle state, the
 *     success path (payload stored, ``run`` resolves the payload, ``loading``
 *     settles false), the in-flight ``loading`` toggle, and — critically — that
 *     the client-normalized ``ApiError`` outcomes are SURFACED, never masked:
 *     the HTTP ``409`` optimistic-lock conflict flag (AAP 0.6.2), the HTTP
 *     ``401`` (surfaced without the hook redirecting), and a non-``ApiError``
 *     failure normalized to ``status`` ``0`` with its message preserved. Also
 *     covers ``reset``.
 * :note: Uses the real ``ApiError``; ``apiFn`` is a plain Jest mock, so the hook's own
 *     state machine is exercised with no network of its own. ``../api/auth`` is mocked
 *     because the hook reads the signed-on identity (to drop held data when it
 *     changes) and the session store answers that from the server: the mock keeps the
 *     probe from reaching the network while leaving ``../api/client`` — and therefore
 *     the real ``ApiError`` — untouched.
 */

import { renderHook, act } from '@testing-library/react';
// Jest's native-ESM runtime does not inject ``jest`` as a global (unlike
// ``describe`` / ``it`` / ``expect``), so the mock factory is imported explicitly.
import { jest } from '@jest/globals';

jest.unstable_mockModule('../api/auth', () => ({
  __esModule: true,
  signon: jest.fn(),
  // Never settles: the identity is irrelevant to this hook's state machine, and an
  // unresolved probe leaves the session store untouched for the duration of the file.
  getSessionIdentity: (): Promise<never> => new Promise<never>(() => undefined),
  logout: (): Promise<void> => Promise.resolve(),
}));

const { useApi } = await import('./useApi');
const { ApiError } = await import('../api');

/** Minimal wire-shaped payload; fields kept as strings to mirror preserved wire formats. */
interface Payload {
  id: string;
  amount: string;
}

const payload: Payload = { id: '00000000001', amount: '123.45' };

const OPTIMISTIC_LOCK_MESSAGE = 'Record changed by some one else. Please review';

describe('useApi — initial state', () => {
  it('starts idle: data null, loading false, error null, no conflict', () => {
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    const { result } = renderHook(() => useApi(apiFn));

    expect(result.current.data).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.isOptimisticLockConflict).toBe(false);
    // Trigger-driven: the hook does not auto-run on mount.
    expect(apiFn).not.toHaveBeenCalled();
  });
});

describe('useApi — success path', () => {
  it('stores the payload, forwards args, resolves run to the payload', async () => {
    const apiFn = jest.fn(
      (_accountId: string): Promise<Payload> => Promise.resolve(payload),
    );
    const { result } = renderHook(() => useApi(apiFn));

    let returned: Payload | undefined;
    await act(async () => {
      returned = await result.current.run('00000000001');
    });

    expect(apiFn).toHaveBeenCalledTimes(1);
    expect(apiFn).toHaveBeenCalledWith('00000000001');
    expect(returned).toEqual(payload);
    expect(result.current.data).toEqual(payload);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.isOptimisticLockConflict).toBe(false);
  });

  it('toggles loading true while in flight and false after it resolves', async () => {
    let resolveFn!: (value: Payload) => void;
    const deferred = new Promise<Payload>((resolve) => {
      resolveFn = resolve;
    });
    const apiFn = jest.fn((): Promise<Payload> => deferred);
    const { result } = renderHook(() => useApi(apiFn));

    expect(result.current.loading).toBe(false);

    let runPromise!: Promise<Payload | undefined>;
    act(() => {
      runPromise = result.current.run();
    });

    // While the deferred promise is pending, the hook reports loading.
    expect(result.current.loading).toBe(true);
    expect(result.current.error).toBeNull();

    await act(async () => {
      resolveFn(payload);
      await runPromise;
    });

    expect(result.current.loading).toBe(false);
    expect(result.current.data).toEqual(payload);
  });
});

describe('useApi — surfaces client-normalized errors (never masks)', () => {
  it('surfaces the 409 optimistic-lock conflict and drops the stale payload', async () => {
    const conflict = new ApiError(409, OPTIMISTIC_LOCK_MESSAGE, undefined, true);
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    const { result } = renderHook(() => useApi(apiFn));

    // Seed a successful read, then prove the failure that follows clears it.
    await act(async () => {
      await result.current.run();
    });
    expect(result.current.data).toEqual(payload);

    // Next call rejects with the 409 conflict.
    apiFn.mockRejectedValueOnce(conflict);
    let returned: Payload | undefined = payload;
    await act(async () => {
      returned = await result.current.run();
    });

    expect(returned).toBeUndefined();
    expect(result.current.error).toBe(conflict);
    expect(result.current.error?.status).toBe(409);
    expect(result.current.error?.isOptimisticLockConflict).toBe(true);
    expect(result.current.error?.message).toBe(OPTIMISTIC_LOCK_MESSAGE);
    expect(result.current.isOptimisticLockConflict).toBe(true);
    // The held payload described the call that just failed, so it is not left on
    // screen beside the failure message.
    expect(result.current.data).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a 401 without redirecting (redirect is the client\'s job)', async () => {
    const unauthorized = new ApiError(401, 'Unauthorized');
    const apiFn = jest.fn((): Promise<Payload> => Promise.reject(unauthorized));
    const { result } = renderHook(() => useApi(apiFn));

    const pathBefore = window.location.pathname;
    let returned: Payload | undefined = payload;
    await act(async () => {
      returned = await result.current.run();
    });

    expect(returned).toBeUndefined();
    expect(result.current.error).toBe(unauthorized);
    expect(result.current.error?.status).toBe(401);
    expect(result.current.isOptimisticLockConflict).toBe(false);
    // The hook performs no navigation; the location is untouched.
    expect(window.location.pathname).toBe(pathBefore);
  });

  it('normalizes a plain Error to an ApiError with status 0 and preserved message', async () => {
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    apiFn.mockRejectedValueOnce(new Error('boom network'));
    const { result } = renderHook(() => useApi(apiFn));

    let returned: Payload | undefined = payload;
    await act(async () => {
      returned = await result.current.run();
    });

    expect(returned).toBeUndefined();
    expect(result.current.error).toBeInstanceOf(ApiError);
    expect(result.current.error?.status).toBe(0);
    expect(result.current.error?.message).toBe('boom network');
    expect(result.current.error?.isOptimisticLockConflict).toBe(false);
  });

  it('normalizes a non-Error rejection to status 0 with the generic message', async () => {
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    apiFn.mockRejectedValueOnce('a non-error string');
    const { result } = renderHook(() => useApi(apiFn));

    await act(async () => {
      await result.current.run();
    });

    expect(result.current.error).toBeInstanceOf(ApiError);
    expect(result.current.error?.status).toBe(0);
    expect(result.current.error?.message).toBe('Unexpected error');
  });
});

describe('useApi — reset', () => {
  it('clears data, error and loading back to the initial state', async () => {
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    const { result } = renderHook(() => useApi(apiFn));

    await act(async () => {
      await result.current.run();
    });
    expect(result.current.data).toEqual(payload);

    act(() => {
      result.current.reset();
    });

    expect(result.current.data).toBeNull();
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.isOptimisticLockConflict).toBe(false);
  });

  it('clears a captured error', async () => {
    const apiFn = jest.fn((): Promise<Payload> =>
      Promise.reject(new ApiError(500, 'Internal error')),
    );
    const { result } = renderHook(() => useApi(apiFn));

    await act(async () => {
      await result.current.run();
    });
    expect(result.current.error?.status).toBe(500);

    act(() => {
      result.current.reset();
    });
    expect(result.current.error).toBeNull();
  });
});

describe('useApi — supersession', () => {
  it('commits only the newest call when an earlier one resolves last', async () => {
    const first: Payload = { id: '00000000001', amount: '1.00' };
    const second: Payload = { id: '00000000002', amount: '2.00' };
    let resolveFirst!: (value: Payload) => void;
    let resolveSecond!: (value: Payload) => void;
    const apiFn = jest
      .fn<() => Promise<Payload>>()
      .mockImplementationOnce(
        () =>
          new Promise<Payload>((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<Payload>((resolve) => {
            resolveSecond = resolve;
          }),
      );
    const { result } = renderHook(() => useApi(apiFn));

    let firstRun!: Promise<Payload | undefined>;
    let secondRun!: Promise<Payload | undefined>;
    act(() => {
      firstRun = result.current.run();
      secondRun = result.current.run();
    });

    // The newest call settles first and is committed.
    await act(async () => {
      resolveSecond(second);
      await secondRun;
    });
    expect(result.current.data).toEqual(second);

    // The superseded call settles afterwards and must be discarded entirely.
    let firstReturned: Payload | undefined = second;
    await act(async () => {
      resolveFirst(first);
      firstReturned = await firstRun;
    });
    expect(firstReturned).toBeUndefined();
    expect(result.current.data).toEqual(second);
    expect(result.current.loading).toBe(false);
  });

  it('aborts the superseded call and publishes the newest signal', async () => {
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    const { result } = renderHook(() => useApi(apiFn));

    await act(async () => {
      await result.current.run();
    });
    const firstSignal = result.current.signal;
    expect(firstSignal).not.toBeNull();
    expect(firstSignal?.aborted).toBe(false);

    await act(async () => {
      await result.current.run();
    });
    expect(firstSignal?.aborted).toBe(true);
    expect(result.current.signal).not.toBe(firstSignal);
    expect(result.current.signal?.aborted).toBe(false);
  });

  it('aborts the call in flight when the screen is left', async () => {
    let resolveFn!: (value: Payload) => void;
    const apiFn = jest.fn(
      (): Promise<Payload> =>
        new Promise<Payload>((resolve) => {
          resolveFn = resolve;
        }),
    );
    const { result, unmount } = renderHook(() => useApi(apiFn));

    let pending!: Promise<Payload | undefined>;
    act(() => {
      pending = result.current.run();
    });
    const signal = result.current.signal;
    expect(signal?.aborted).toBe(false);

    unmount();
    expect(signal?.aborted).toBe(true);

    // The late response is discarded rather than written to an unmounted screen.
    resolveFn(payload);
    await expect(pending).resolves.toBeUndefined();
  });
});
