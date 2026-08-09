/**
 * :module: useSession.test
 * :purpose: Verify the SERVER-authoritative session/role hook: the signed-out initial
 *     state, admin (``'A'``) versus standard-user (``'U'``) gating that mirrors the
 *     ``COSGN00C`` admin/user routing, verbatim credential pass-through (no
 *     upper-casing), that no authority is ever taken from browser storage, that
 *     ``refresh`` republishes the identity the server holds, that ``signOut`` revokes
 *     the server session BEFORE clearing local state and keeps the session when that
 *     revocation fails, that a centralized ``401``/``403`` expiry drops local
 *     authority and raises a visible session-ended notice (while a ``403`` refusal
 *     stays silent, because the refusing program reports that itself), a surfaced failed sign-in, and the single module-level store shared
 *     across hook instances.
 * :note: ``../api`` is mocked via ``jest.unstable_mockModule`` so no real network,
 *     axios, or Vite ``import.meta`` is touched. ``useSession`` is loaded once (sharing
 *     React with the statically imported Testing Library); the shared module store is
 *     reset to signed-out after each test.
 */

import { jest } from '@jest/globals';
import { renderHook, act, waitFor } from '@testing-library/react';
import type {
  Role,
  SessionIdentityDto,
  SignonRequestDto,
  SignonResponseDto,
  SessionContext,
} from '../types';

/**
 * Stable mocks for the ``../api`` named exports the hook binds to, reset before each
 * test. Declared at module scope so the ``unstable_mockModule`` factory can close over
 * them.
 */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();
const getSessionIdentityMock = jest.fn<() => Promise<SessionIdentityDto>>();
const logoutMock = jest.fn<() => Promise<void>>();

/**
 * Captures the expiry callback the hook registers, so a test can fire the centralized
 * ``401``/``403`` path exactly as the axios response interceptor would.
 */
let expiryHandler: ((reason: 'expired' | 'refused') => void) | undefined;

jest.unstable_mockModule('../api', () => ({
  // The request-cancellation contract ``useApi`` binds to: the real scope hands the
  // caller's AbortSignal to axios, and the double simply invokes the call.
  runWithRequestSignal: (_signal: AbortSignal, call: () => unknown): unknown => call(),
  isCancelledRequest: (): boolean => false,
  __esModule: true,
  signon: signonMock,
  getSessionIdentity: getSessionIdentityMock,
  logout: logoutMock,
  registerSessionExpiryHandler: (handler: (reason: 'expired' | 'refused') => void) => {
    expiryHandler = handler;
    return () => {
      expiryHandler = undefined;
    };
  },
}));

/**
 * Minimal ``ApiError`` stand-in for the failed sign-in and failed-logout paths,
 * avoiding any load of the real ``../api/client`` (which transitively reads
 * ``import.meta``).
 */
class FakeApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

type UseSessionHook = (typeof import('./useSession'))['useSession'];

let useSession: UseSessionHook;

/**
 * The row-23 copy an expired cookie session raises, read from the hook module so the
 * test asserts against the shipped literal rather than a duplicate of it.
 */
let SESSION_ENDED_MESSAGE: string;

/**
 * :purpose: Render the hook and wait for its one-time server identity probe to settle,
 *     so a test never asserts against the pre-probe frame.
 * :returns: the rendered hook handle.
 */
async function renderSession() {
  const handle = renderHook(() => useSession());
  await waitFor(() => {
    expect(getSessionIdentityMock).toHaveBeenCalled();
  });
  return handle;
}

/**
 * :purpose: Publish an identity through the real sign-in path.
 * :param userId: the user id the server reports.
 * :param userType: the role the server reports.
 * :returns: the rendered hook handle, signed in.
 */
async function signedIn(userId: string, userType: Role) {
  signonMock.mockResolvedValue({
    userId,
    userType,
    redirectTarget: userType === 'A' ? 'CA00' : 'CM00',
  });
  const handle = await renderSession();
  await act(async () => {
    await handle.result.current.signIn(userId, 'pw');
  });
  return handle;
}

beforeAll(async () => {
  // Imported after the mock is registered so the hook binds to the mocked API; no
  // module reset, so it shares React with Testing Library.
  ({ useSession, SESSION_ENDED_MESSAGE } = await import('./useSession'));
});

beforeEach(() => {
  signonMock.mockReset();
  logoutMock.mockReset();
  getSessionIdentityMock.mockReset();
  // The default server answer is "no usable session"; a test that wants an identity
  // overrides it.
  getSessionIdentityMock.mockRejectedValue(new FakeApiError(401, 'Unauthorized'));
  logoutMock.mockResolvedValue(undefined);
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.clear();
  }
});

afterEach(async () => {
  // Return the shared module store to signed-out so no state leaks across tests. The
  // revocation call is forced to succeed first, because a test that exercised a failed
  // revocation deliberately leaves the session live.
  logoutMock.mockReset();
  logoutMock.mockResolvedValue(undefined);
  const { result, unmount } = renderHook(() => useSession());
  await act(async () => {
    await result.current.signOut();
  });
  unmount();
});

describe('useSession — initial state', () => {
  it('starts signed out with no user, role, or session', async () => {
    const { result } = await renderSession();

    expect(result.current.user).toBeNull();
    expect(result.current.role).toBeNull();
    expect(result.current.session).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.isAdmin).toBe(false);
  });
});

describe('useSession — sign-in role gating (COSGN00C A vs U)', () => {
  it('signs in an admin and derives isAdmin from role "A"', async () => {
    signonMock.mockResolvedValue({
      userId: 'ADMIN001',
      userType: 'A',
      redirectTarget: 'CA00',
    });
    const { result } = await renderSession();

    let returned: SessionContext | SignonResponseDto | undefined;
    await act(async () => {
      returned = await result.current.signIn('ADMIN001', 'pw');
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.role).toBe('A');
    expect(result.current.isAdmin).toBe(true);
    expect(result.current.user).toBe('ADMIN001');
    expect(result.current.session).toEqual({
      userId: 'ADMIN001',
      userType: 'A',
      programContext: 0,
    });
    // Credentials are forwarded verbatim — not upper-cased (AAP 0.6.7).
    expect(signonMock).toHaveBeenCalledWith({
      userId: 'ADMIN001',
      password: 'pw',
    });
    expect(returned).toEqual({
      userId: 'ADMIN001',
      userType: 'A',
      redirectTarget: 'CA00',
    });
  });

  it('signs in a standard user: authenticated but NOT admin (role "U")', async () => {
    signonMock.mockResolvedValue({
      userId: 'USER0001',
      userType: 'U',
      redirectTarget: 'CM00',
    });
    const { result } = await renderSession();

    await act(async () => {
      await result.current.signIn('USER0001', 'pw');
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.role).toBe('U');
    expect(result.current.isAdmin).toBe(false);
    expect(result.current.user).toBe('USER0001');
  });

  it('forwards mixed-case credentials without any transformation', async () => {
    signonMock.mockResolvedValue({
      userId: 'AdMiN001',
      userType: 'A',
      redirectTarget: 'CA00',
    });
    const { result } = await renderSession();

    await act(async () => {
      await result.current.signIn('AdMiN001', 'MixedCasePw');
    });

    expect(signonMock).toHaveBeenCalledWith({
      userId: 'AdMiN001',
      password: 'MixedCasePw',
    });
  });
});

describe('useSession — the server is the authority', () => {
  it('stores no authority in browser storage on sign-in', async () => {
    const { result } = await signedIn('ADMIN001', 'A');

    expect(result.current.isAuthenticated).toBe(true);
    // Nothing about the identity, the role or the credential is written anywhere a
    // client can edit; the role that gates the UI came from the server response only.
    const written: string[] = [];
    for (let index = 0; index < sessionStorage.length; index += 1) {
      const key = sessionStorage.key(index);
      if (key !== null) {
        written.push(`${key}=${sessionStorage.getItem(key) ?? ''}`);
      }
    }
    expect(written.join('|')).not.toContain('ADMIN001');
    expect(written.join('|')).not.toContain('pw');
  });

  it('republishes the identity the server holds on refresh', async () => {
    const { result } = await renderSession();
    expect(result.current.isAuthenticated).toBe(false);

    getSessionIdentityMock.mockResolvedValue({ userId: 'ADMIN001', userType: 'A' });
    let recovered: boolean | undefined;
    await act(async () => {
      recovered = await result.current.refresh();
    });

    expect(recovered).toBe(true);
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user).toBe('ADMIN001');
    expect(result.current.isAdmin).toBe(true);
  });

  it('refresh reports no session and stays signed out when the server refuses', async () => {
    const { result } = await signedIn('ADMIN001', 'A');
    expect(result.current.isAuthenticated).toBe(true);

    getSessionIdentityMock.mockRejectedValue(new FakeApiError(401, 'Unauthorized'));
    let recovered: boolean | undefined;
    await act(async () => {
      recovered = await result.current.refresh();
    });

    expect(recovered).toBe(false);
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.role).toBeNull();
  });

  it('rejects an identity whose role code is not a legacy CDEMO-USER-TYPE value', async () => {
    const { result } = await renderSession();

    getSessionIdentityMock.mockResolvedValue({
      userId: 'ADMIN001',
      userType: 'X' as Role,
    });
    let recovered: boolean | undefined;
    await act(async () => {
      recovered = await result.current.refresh();
    });

    expect(recovered).toBe(false);
    expect(result.current.isAuthenticated).toBe(false);
  });
});

describe('useSession — sign-in revokes a live session first', () => {
  it('revokes the live session before sending the new credentials', async () => {
    const { result } = await signedIn('USER0001', 'U');
    expect(result.current.isAuthenticated).toBe(true);

    const order: string[] = [];
    logoutMock.mockImplementation(() => {
      order.push('logout');
      return Promise.resolve();
    });
    signonMock.mockImplementation(() => {
      order.push('signon');
      return Promise.resolve({ userId: 'ADMIN001', userType: 'A', redirectTarget: 'CA00' });
    });

    await act(async () => {
      await result.current.signIn('ADMIN001', 'pw');
    });

    expect(order).toEqual(['logout', 'signon']);
    expect(result.current.user).toBe('ADMIN001');
    expect(result.current.isAdmin).toBe(true);
  });

  it('sends the credentials directly when no session is held', async () => {
    signonMock.mockResolvedValue({
      userId: 'USER0001',
      userType: 'U',
      redirectTarget: 'CM00',
    });
    const { result } = await renderSession();
    expect(result.current.isAuthenticated).toBe(false);

    await act(async () => {
      await result.current.signIn('USER0001', 'pw');
    });

    expect(logoutMock).not.toHaveBeenCalled();
    expect(signonMock).toHaveBeenCalledTimes(1);
    expect(result.current.user).toBe('USER0001');
  });
});

describe('useSession — signOut revokes the server session first', () => {
  it('awaits POST /logout, then clears the local session', async () => {
    const { result } = await signedIn('ADMIN001', 'A');
    expect(result.current.isAuthenticated).toBe(true);

    // The local session must still be authoritative while the revocation is in flight,
    // so a signed-out screen is never presented over a session cookie that is live.
    let authenticatedDuringLogout: boolean | undefined;
    logoutMock.mockImplementation(() => {
      authenticatedDuringLogout = result.current.isAuthenticated;
      return Promise.resolve();
    });

    await act(async () => {
      await result.current.signOut();
    });

    expect(logoutMock).toHaveBeenCalledTimes(1);
    expect(authenticatedDuringLogout).toBe(true);
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(result.current.role).toBeNull();
    expect(result.current.session).toBeNull();
  });

  it('keeps the session and surfaces the failure when revocation fails', async () => {
    const { result } = await signedIn('ADMIN001', 'A');
    logoutMock.mockRejectedValue(new FakeApiError(503, 'Service Unavailable'));

    let caught: unknown;
    await act(async () => {
      try {
        await result.current.signOut();
      } catch (error) {
        caught = error;
      }
    });

    expect(caught).toBeInstanceOf(FakeApiError);
    // The server session is still live, so the UI must not claim to be signed out.
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user).toBe('ADMIN001');
  });
});

describe('useSession — centralized session expiry', () => {
  it('drops local authority when a request reports the session is gone', async () => {
    const { result } = await signedIn('ADMIN001', 'A');
    expect(result.current.isAuthenticated).toBe(true);
    expect(expiryHandler).toBeDefined();

    act(() => {
      expiryHandler?.('expired');
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.role).toBeNull();
    expect(result.current.session).toBeNull();
  });

  it('publishes the session-ended notice for an expiry, so it is never silent', async () => {
    const { result } = await signedIn('ADMIN001', 'A');
    expect(result.current.sessionNotice).toBeNull();

    act(() => {
      expiryHandler?.('expired');
    });

    expect(result.current.sessionNotice).toBe(SESSION_ENDED_MESSAGE);
    expect(result.current.isSessionResolved).toBe(true);
  });

  it('keeps the notice through the identity probe the expiry triggers', async () => {
    // The probe that follows an expiry answers "no session"; it must confirm the
    // signed-out state without erasing the very message the expiry raised.
    const { result } = await signedIn('ADMIN001', 'A');
    act(() => {
      expiryHandler?.('expired');
    });
    expect(result.current.sessionNotice).toBe(SESSION_ENDED_MESSAGE);

    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.sessionNotice).toBe(SESSION_ENDED_MESSAGE);
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('publishes NO notice for a refusal — the refusing program reports that itself', async () => {
    const { result } = await signedIn('ADMIN001', 'A');

    act(() => {
      expiryHandler?.('refused');
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.sessionNotice).toBeNull();
  });

  it('withdraws the notice once it has been reported, and on a fresh sign-on', async () => {
    const { result } = await signedIn('ADMIN001', 'A');
    act(() => {
      expiryHandler?.('expired');
    });
    expect(result.current.sessionNotice).toBe(SESSION_ENDED_MESSAGE);

    act(() => {
      result.current.clearSessionNotice();
    });
    expect(result.current.sessionNotice).toBeNull();

    // And a completed sign-on leaves nothing to report either.
    act(() => {
      expiryHandler?.('expired');
    });
    expect(result.current.sessionNotice).toBe(SESSION_ENDED_MESSAGE);
    const signedOn = await signedIn('USER0001', 'U');
    expect(signedOn.result.current.sessionNotice).toBeNull();
  });
});

describe('useSession — failed sign-in', () => {
  it('surfaces the error to the caller and stays signed out', async () => {
    signonMock.mockRejectedValue(
      new FakeApiError(401, 'Wrong Password. Try again ...'),
    );
    const { result } = await renderSession();

    let caught: unknown;
    await act(async () => {
      try {
        await result.current.signIn('BADUSER0', 'wrong');
      } catch (error) {
        caught = error;
      }
    });

    expect(caught).toBeInstanceOf(FakeApiError);
    expect((caught as FakeApiError).status).toBe(401);
    expect((caught as FakeApiError).message).toContain('Wrong Password');
    // The rejected sign-in must not establish a session.
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(result.current.role).toBeNull();
  });
});

describe('useSession — shared module store (provider-free)', () => {
  it('reflects a sign-in from one hook instance in another', async () => {
    signonMock.mockResolvedValue({
      userId: 'USER0001',
      userType: 'U',
      redirectTarget: 'CM00',
    });
    const { result: a } = await renderSession();
    const { result: b } = await renderSession();

    expect(a.current.isAuthenticated).toBe(false);
    expect(b.current.isAuthenticated).toBe(false);

    await act(async () => {
      await a.current.signIn('USER0001', 'pw');
    });

    // Both instances observe the one module-level store.
    expect(a.current.isAuthenticated).toBe(true);
    expect(b.current.isAuthenticated).toBe(true);
    expect(b.current.role).toBe('U');
    expect(b.current.isAdmin).toBe(false);
    expect(b.current.user).toBe('USER0001');
  });
});
