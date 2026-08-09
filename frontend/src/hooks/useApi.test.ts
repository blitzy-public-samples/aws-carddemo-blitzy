/**
 * :module: useApi.test
 * :purpose: Verify the generic async-request hook: the initial idle state, the
 *     success path (payload stored, ``run`` resolves the payload, ``loading``
 *     settles false), the in-flight ``loading`` toggle, and — critically — that
 *     the client-normalized ``ApiError`` outcomes are SURFACED, never masked:
 *     the HTTP ``409`` optimistic-lock conflict flag (AAP 0.6.2), the HTTP
 *     ``401`` (surfaced without the hook redirecting), and a non-``ApiError``
 *     failure normalized to ``status`` ``0`` with its message preserved. Also
 *     covers ``reset``, the cancellation contract (the bound ``AbortSignal`` really
 *     reaches axios, a superseded request really is aborted, and a cancellation is
 *     reported as nothing at all), and the ``isInFlight`` terminal lock.
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
import type {
  AxiosAdapter,
  GenericAbortSignal,
  InternalAxiosRequestConfig,
} from 'axios';

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
const { GENERIC_ERROR_MESSAGE } = await import('../api/messages');
// The real shared client: its request interceptor is what stamps the bound signal on
// the outgoing request, so the cancellation contract is asserted through it rather
// than against a private variable.
const { default: apiClient } = await import('../api/client');

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
  it('surfaces the 409 optimistic-lock conflict and keeps the displayed payload', async () => {
    const conflict = new ApiError(409, OPTIMISTIC_LOCK_MESSAGE, undefined, true);
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    const { result } = renderHook(() => useApi(apiFn));

    // Seed a successful read, then prove the failure that follows reports itself
    // WITHOUT clearing what the operator is looking at.
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
    // A failed turn re-presents the map the operator was reading with the message
    // beside it, so the payload already on screen is kept rather than cleared.
    expect(result.current.data).toEqual(payload);
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

  it('normalizes a plain Error to an ApiError with status 0 and the generic message', async () => {
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
    // A value that never passed through the client interceptor carries a library or
    // programming diagnostic, which the BMS line-23 field must never render, so the
    // application's own generic stands in for it.
    expect(result.current.error?.message).toBe(GENERIC_ERROR_MESSAGE);
    expect(result.current.error?.message).not.toContain('boom network');
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
    expect(result.current.error?.message).toBe(GENERIC_ERROR_MESSAGE);
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

describe('useApi — cancellation reaches the transport', () => {
  /**
   * Signals axios was handed, newest last, recorded by the stub adapter. Typed as
   * axios types the field (``GenericAbortSignal``), which the assertions then narrow
   * to the real ``AbortSignal`` the hook binds.
   */
  let carried: (GenericAbortSignal | undefined)[] = [];

  beforeEach(() => {
    carried = [];
    const adapter: AxiosAdapter = (config: InternalAxiosRequestConfig) => {
      carried.push(config.signal);
      return Promise.resolve({
        data: payload,
        status: 200,
        statusText: 'OK',
        headers: config.headers,
        config,
      });
    };
    apiClient.defaults.adapter = adapter;
  });

  /**
   * :purpose: An api function shaped like the real ones — it issues exactly one
   *     request through the shared client before it awaits.
   * :returns: the stubbed payload.
   */
  const readThroughClient = async (): Promise<Payload> => {
    const response = await apiClient.get<Payload>('/probe');
    return response.data;
  };

  it('hands the call\'s abort signal to axios and aborts it when superseded', async () => {
    const { result } = renderHook(() => useApi(readThroughClient));

    await act(async () => {
      await result.current.run();
    });
    expect(carried).toHaveLength(1);
    expect(carried[0]).toBeInstanceOf(AbortSignal);
    expect(carried[0]).toBe(result.current.signal);
    expect(carried[0]?.aborted).toBe(false);

    await act(async () => {
      await result.current.run();
    });
    expect(carried).toHaveLength(2);
    // The request the first call issued really was cancelled, not merely ignored.
    expect(carried[0]?.aborted).toBe(true);
    expect(carried[1]).toBe(result.current.signal);
    expect(carried[1]?.aborted).toBe(false);
  });

  it('binds nothing to a request issued outside a call', async () => {
    // The binding covers only the request a call issues; an unrelated request must
    // never inherit a signal, or it would be cancelled with someone else's call.
    await apiClient.get('/probe');
    expect(carried).toEqual([undefined]);
  });

  it('reports neither an error nor a loading state for a cancelled request', async () => {
    // A cancelled request is this hook having superseded its own call. It is not a
    // failure and must never reach a screen's message line.
    const cancelled = new ApiError(
      0,
      'canceled',
      undefined,
      false,
      undefined,
      true,
    );
    const apiFn = jest.fn((): Promise<Payload> => Promise.resolve(payload));
    const { result } = renderHook(() => useApi(apiFn));

    await act(async () => {
      await result.current.run();
    });

    apiFn.mockRejectedValueOnce(cancelled);
    let returned: Payload | undefined = payload;
    await act(async () => {
      returned = await result.current.run();
    });

    expect(returned).toBeUndefined();
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.data).toEqual(payload);
  });
});

describe('useApi — the terminal is locked while a call is outstanding', () => {
  it('answers isInFlight synchronously, within the tick that started the call', async () => {
    let resolveFn!: (value: Payload) => void;
    const apiFn = jest.fn(
      (): Promise<Payload> =>
        new Promise<Payload>((resolve) => {
          resolveFn = resolve;
        }),
    );
    const { result } = renderHook(() => useApi(apiFn));

    expect(result.current.isInFlight()).toBe(false);

    let pending!: Promise<Payload | undefined>;
    act(() => {
      pending = result.current.run();
      // Same tick as the call: `loading` has not re-rendered yet, but the lock is on,
      // which is what lets a screen inhibit a second attention identifier.
      expect(result.current.isInFlight()).toBe(true);
    });

    await act(async () => {
      resolveFn(payload);
      await pending;
    });

    expect(result.current.isInFlight()).toBe(false);
    expect(result.current.loading).toBe(false);
  });

  it('keeps the lock on while a superseded call settles before its successor', async () => {
    const resolvers: ((value: Payload) => void)[] = [];
    const apiFn = jest.fn(
      (): Promise<Payload> =>
        new Promise<Payload>((resolve) => {
          resolvers.push(resolve);
        }),
    );
    const { result } = renderHook(() => useApi(apiFn));

    let first!: Promise<Payload | undefined>;
    let second!: Promise<Payload | undefined>;
    act(() => {
      first = result.current.run();
      second = result.current.run();
    });
    expect(result.current.isInFlight()).toBe(true);

    // The superseded call settles first; the terminal is still waiting for the newest.
    await act(async () => {
      resolvers[0](payload);
      await first;
    });
    expect(result.current.isInFlight()).toBe(true);

    await act(async () => {
      resolvers[1](payload);
      await second;
    });
    expect(result.current.isInFlight()).toBe(false);
  });

  it('releases the lock when a call fails and when reset clears the hook', async () => {
    const apiFn = jest.fn((): Promise<Payload> =>
      Promise.reject(new ApiError(500, 'Internal error')),
    );
    const { result } = renderHook(() => useApi(apiFn));

    await act(async () => {
      await result.current.run();
    });
    expect(result.current.isInFlight()).toBe(false);
    expect(result.current.error?.status).toBe(500);

    act(() => {
      result.current.reset();
    });
    expect(result.current.isInFlight()).toBe(false);
  });
});
