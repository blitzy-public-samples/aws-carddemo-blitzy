/**
 * :module: useSession.test
 * :purpose: Verify the provider-free session/role hook: signed-out initial
 *     state, admin (``'A'``) versus standard-user (``'U'``) gating that mirrors
 *     the ``COSGN00C`` admin/user routing, verbatim credential pass-through (no
 *     upper-casing), password-free ``sessionStorage`` persistence and
 *     rehydration, ``signOut`` clearing, a surfaced failed sign-in, and the
 *     single module-level store shared across hook instances.
 * :note: ``../api`` is mocked via ``jest.unstable_mockModule`` so no real
 *     network, axios, or Vite ``import.meta`` is touched. ``useSession`` is
 *     loaded once (sharing React with the statically imported Testing Library);
 *     the shared module store is reset to signed-out after each test. The
 *     rehydration case reloads the module inside ``jest.isolateModulesAsync``,
 *     re-importing Testing Library alongside it so both share one React
 *     instance.
 */

import { jest } from '@jest/globals';
import { renderHook, act } from '@testing-library/react';
import type {
  SignonRequestDto,
  SignonResponseDto,
  SessionContext,
} from '../types';

/**
 * Stable mock for the ``../api`` ``signon`` named export, reset before each
 * test. Declared at module scope so the ``unstable_mockModule`` factory can
 * close over it.
 */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

jest.unstable_mockModule('../api', () => ({
  __esModule: true,
  signon: signonMock,
}));

/**
 * Minimal ``ApiError`` stand-in for the failed sign-in path, avoiding any load
 * of the real ``../api/client`` (which transitively reads ``import.meta``).
 */
class FakeApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

const STORAGE_KEY = 'carddemo.session';

type UseSessionHook = (typeof import('./useSession'))['useSession'];

let useSession: UseSessionHook;

beforeAll(async () => {
  // Imported after the mock is registered so the hook binds to the mocked
  // ``signon``; no module reset, so it shares React with Testing Library.
  ({ useSession } = await import('./useSession'));
});

beforeEach(() => {
  signonMock.mockReset();
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.clear();
  }
});

afterEach(async () => {
  // Return the shared module store to signed-out so no state leaks across tests.
  const { result, unmount } = renderHook(() => useSession());
  await act(async () => {
    await result.current.signOut();
  });
  unmount();
});

describe('useSession — initial state', () => {
  it('starts signed out with no user, role, or session', () => {
    const { result } = renderHook(() => useSession());

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
    const { result } = renderHook(() => useSession());

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
    const { result } = renderHook(() => useSession());

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
    const { result } = renderHook(() => useSession());

    await act(async () => {
      await result.current.signIn('AdMiN001', 'MixedCasePw');
    });

    expect(signonMock).toHaveBeenCalledWith({
      userId: 'AdMiN001',
      password: 'MixedCasePw',
    });
  });
});

describe('useSession — persistence and rehydration (password-free)', () => {
  it('persists user + role but never a password', async () => {
    signonMock.mockResolvedValue({
      userId: 'ADMIN001',
      userType: 'A',
      redirectTarget: 'CA00',
    });
    const { result } = renderHook(() => useSession());

    await act(async () => {
      await result.current.signIn('ADMIN001', 'SENTINEL_PW');
    });

    const raw = sessionStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw as string) as { user: string; role: string };
    expect(parsed.user).toBe('ADMIN001');
    expect(parsed.role).toBe('A');
    // The persisted blob carries no password material.
    expect(raw as string).not.toContain('SENTINEL_PW');
    expect((raw as string).toLowerCase()).not.toContain('password');
  });

  it('rehydrates a persisted session into a freshly loaded module', async () => {
    signonMock.mockResolvedValue({
      userId: 'ADMIN001',
      userType: 'A',
      redirectTarget: 'CA00',
    });
    const { result } = renderHook(() => useSession());
    await act(async () => {
      await result.current.signIn('ADMIN001', 'pw');
    });
    expect(sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();

    // Reload the module (simulating a page reload) inside an isolated module
    // registry. React, react-dom, and the hook are all re-imported here so they
    // share one React instance; a minimal manual render (no Testing Library,
    // which would re-register lifecycle hooks) observes the rehydrated store.
    await jest.isolateModulesAsync(async () => {
      const react = await import('react');
      const reactDomClient = await import('react-dom/client');
      const { useSession: reloadedUseSession } = await import('./useSession');

      let snapshot: ReturnType<UseSessionHook> | undefined;
      const Probe = (): null => {
        snapshot = reloadedUseSession();
        return null;
      };

      const container = document.createElement('div');
      const root = reactDomClient.createRoot(container);
      await react.act(async () => {
        root.render(react.createElement(Probe));
      });

      expect(snapshot?.isAuthenticated).toBe(true);
      expect(snapshot?.role).toBe('A');
      expect(snapshot?.isAdmin).toBe(true);
      expect(snapshot?.user).toBe('ADMIN001');

      await react.act(async () => {
        root.unmount();
      });
    });
  });
});

describe('useSession — signOut', () => {
  it('clears state and removes the persisted session', async () => {
    signonMock.mockResolvedValue({
      userId: 'ADMIN001',
      userType: 'A',
      redirectTarget: 'CA00',
    });
    const { result } = renderHook(() => useSession());

    await act(async () => {
      await result.current.signIn('ADMIN001', 'pw');
    });
    expect(result.current.isAuthenticated).toBe(true);
    expect(sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();

    await act(async () => {
      await result.current.signOut();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(result.current.role).toBeNull();
    expect(result.current.session).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe('useSession — failed sign-in', () => {
  it('surfaces the error to the caller and stays signed out', async () => {
    signonMock.mockRejectedValue(
      new FakeApiError(401, 'Wrong Password. Try again ...'),
    );
    const { result } = renderHook(() => useSession());

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
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe('useSession — shared module store (provider-free)', () => {
  it('reflects a sign-in from one hook instance in another', async () => {
    signonMock.mockResolvedValue({
      userId: 'USER0001',
      userType: 'U',
      redirectTarget: 'CM00',
    });
    const { result: a } = renderHook(() => useSession());
    const { result: b } = renderHook(() => useSession());

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
