/**
 * App.test
 * ========
 *
 * :purpose: Verify the single-page-application route table: the seventeen screens
 *     that replace the BMS mapsets each mount inside the shared terminal shell,
 *     unauthenticated access to a protected screen falls back to the sign-on
 *     screen, and the administrator-only screens (``COADM01`` plus
 *     ``COUSR00``-``COUSR03``) refuse a standard user, mirroring the COMMAREA
 *     ``CDEMO-USER-TYPE`` gating of the legacy ``XCTL`` transfers.
 * :note: ``./api/client`` is mocked through ``jest.unstable_mockModule`` so no
 *     axios instance, network request, or Vite ``import.meta`` evaluation occurs;
 *     the session store is seeded through the ``__setSession`` seam. ``App`` is
 *     imported dynamically after the mock is registered, sharing one React
 *     instance with the statically imported Testing Library.
 */

import { jest } from '@jest/globals';
import { render, screen, act, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
// The caption literals every screen other than COSGN00 renders.
import { STANDARD_CAPTIONS } from './components/Header';
import type { ReactElement } from 'react';
import type { Role } from './types/session';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from './types/session';

/**
 * Stand-in for the real ``ApiError``. Every mocked request rejects with a
 * transport failure (``status`` 0) so no page reaches a status-specific branch
 * such as the ``401`` sign-on redirect or the ``409`` optimistic-lock conflict.
 */
class MockApiError extends Error {
  readonly status: number;

  readonly isOptimisticLockConflict: boolean;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.isOptimisticLockConflict = false;
  }
}

/** Rejection shared by every mocked verb; no request leaves the test process. */
const requestFailure = (): Promise<never> =>
  Promise.reject(new MockApiError(0, 'Request suppressed in test'));

jest.unstable_mockModule('./api/client', () => ({
  __esModule: true,
  default: {
    get: requestFailure,
    post: requestFailure,
    put: requestFailure,
    delete: requestFailure,
    interceptors: {
      request: { use: () => 0 },
      response: { use: () => 0 },
    },
  },
  ApiError: MockApiError,
  isApiError: (value: unknown): boolean => value instanceof MockApiError,
  // Re-exported by the ``./api`` barrel and bound by the session store.
  clearLocalCredentials: (): void => undefined,
  registerSessionExpiryHandler: (): (() => void) => (): void => undefined,
}));

let App: () => ReactElement;
let setSession: (user: string | null, role: Role | null) => void;

beforeAll(async () => {
  ({ default: App } = await import('./App'));
  ({ __setSession: setSession } = await import('./hooks/useSession'));
});

/**
 * Reports the current router location so a redirect can be asserted on the
 * resolved path and on the ``state.from`` breadcrumb the guards attach.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return (
    <>
      <span data-testid="location">{`${location.pathname}${location.search}`}</span>
      <span data-testid="location-state">{JSON.stringify(location.state)}</span>
    </>
  );
}

/**
 * Mount the application at a single history entry.
 *
 * :param path: initial route.
 */
function renderAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  );
}

/**
 * Seed an authenticated session before mounting.
 *
 * :param user: user id published as ``CDEMO-USER-ID``.
 * :param role: role published as ``CDEMO-USER-TYPE``.
 */
function signInAs(user: string, role: Role): void {
  act(() => {
    setSession(user, role);
  });
}

/**
 * Assert that the screen whose chrome carries ``programName`` is the one on
 * display. Every page publishes its legacy program name into the shared shell,
 * so the header value identifies the mounted screen unambiguously.
 *
 * :param programName: expected legacy program name.
 */
async function expectScreen(programName: string): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(programName);
  });
}

/** Route → legacy program name for every screen reachable by a standard user. */
const USER_SCREENS: ReadonlyArray<readonly [string, string]> = [
  ['/menu', 'COMEN01C'],
  ['/accounts', 'COACTVWC'],
  ['/accounts/00000000011', 'COACTVWC'],
  ['/accounts/update', 'COACTUPC'],
  ['/accounts/00000000011/update', 'COACTUPC'],
  ['/cards', 'COCRDLIC'],
  ['/cards/view', 'COCRDSLC'],
  ['/cards/update', 'COCRDUPC'],
  ['/transactions', 'COTRN00C'],
  ['/transactions/view', 'COTRN01C'],
  ['/transactions/0000000000000001', 'COTRN01C'],
  ['/transactions/add', 'COTRN02C'],
  ['/billpay', 'COBIL00C'],
  ['/reports', 'CORPT00C'],
];

/** Route → legacy program name for every administrator-only screen. */
const ADMIN_SCREENS: ReadonlyArray<readonly [string, string]> = [
  ['/admin', 'COADM01C'],
  ['/users', 'COUSR00C'],
  ['/users/add', 'COUSR01C'],
  ['/users/update', 'COUSR02C'],
  ['/users/delete', 'COUSR03C'],
];

describe('App routing', () => {
  afterEach(() => {
    act(() => {
      setSession(null, null);
    });
  });

  it('serves the sign-on screen without a session', async () => {
    renderAt('/signon');
    await expectScreen('COSGN00C');
  });

  it('frames every screen in the shared terminal shell', async () => {
    signInAs('USER0001', CDEMO_USRTYP_USER);
    renderAt('/menu');
    await expectScreen('COMEN01C');
    expect(screen.getByText(STANDARD_CAPTIONS.tran)).toBeInTheDocument();
    expect(screen.getByTestId('title01')).toBeInTheDocument();
    expect(
      screen.getByRole('toolbar', { name: 'Function keys' }),
    ).toBeInTheDocument();
  });

  describe('unauthenticated access', () => {
    it.each(USER_SCREENS.map(([path]) => path))(
      'redirects %s to the sign-on screen',
      async (path) => {
        renderAt(path);
        await expectScreen('COSGN00C');
        expect(screen.getByTestId('location')).toHaveTextContent('/signon');
      },
    );

    it.each(ADMIN_SCREENS.map(([path]) => path))(
      'redirects the administrator screen %s to the sign-on screen',
      async (path) => {
        renderAt(path);
        await expectScreen('COSGN00C');
        expect(screen.getByTestId('location')).toHaveTextContent('/signon');
      },
    );

    it('carries no breadcrumb of the attempted screen on the sign-on redirect', async () => {
      renderAt('/cards');
      await expectScreen('COSGN00C');
      // Entry begins at COSGN00 for every caller, so the redirect publishes no
      // location state a later screen could resume from.
      expect(screen.getByTestId('location-state')).toHaveTextContent('null');
    });

    it('resolves the entry route to the sign-on screen', async () => {
      renderAt('/');
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });
  });

  describe('standard user', () => {
    it.each(USER_SCREENS)('mounts %s', async (path, programName) => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt(path);
      await expectScreen(programName);
      expect(screen.getByTestId('location')).toHaveTextContent(path);
    });

    it.each(ADMIN_SCREENS.map(([path]) => path))(
      'is refused %s and returns to the main menu',
      async (path) => {
        signInAs('USER0001', CDEMO_USRTYP_USER);
        renderAt(path);
        await expectScreen('COMEN01C');
        expect(screen.getByTestId('location')).toHaveTextContent('/menu');
      },
    );

    it('resolves the entry route to the sign-on screen', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/');
      // No mapset answers '/', so entry begins at COSGN00 whatever the role.
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });
  });

  describe('administrator', () => {
    it.each(ADMIN_SCREENS)('mounts %s', async (path, programName) => {
      signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt(path);
      await expectScreen(programName);
      expect(screen.getByTestId('location')).toHaveTextContent(path);
    });

    it.each(USER_SCREENS)('also mounts the shared screen %s', async (path, programName) => {
      signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt(path);
      await expectScreen(programName);
    });

    it('resolves the entry route to the sign-on screen', async () => {
      signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt('/');
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });
  });

  describe('literal path segments outrank route parameters', () => {
    it('keeps the card filter empty on the card-detail entry screen', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/cards/view');
      await expectScreen('COCRDSLC');
      expect(screen.getByTestId('cardsid')).toHaveValue('');
    });

    it('routes no card number in a path segment', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/cards/4111111111111111');
      // The selected card travels in the router location state, never in a path
      // segment, so no card number reaches the address bar or the session
      // history and no route claims one.
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });

    it('keeps the account key empty on the account-view entry screen', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/accounts');
      await expectScreen('COACTVWC');
      expect(screen.getByLabelText(/Account Number/i)).toHaveValue('');
    });

    it('reaches the account-update screen, not the account view, at /accounts/update', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/accounts/update');
      await expectScreen('COACTUPC');
    });

    it('reaches the add-transaction screen, not the transaction view, at /transactions/add', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/transactions/add');
      await expectScreen('COTRN02C');
    });

    it('reaches the card-update screen, not the card detail, at /cards/update', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/cards/update');
      await expectScreen('COCRDUPC');
    });
  });

  describe('unknown deep link', () => {
    it('sends a signed-in user to the sign-on screen', async () => {
      signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/no-such-screen');
      // No mapset answers any other path; entry begins at the sign-on screen.
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });

    it('sends a visitor without a session to the sign-on screen', async () => {
      renderAt('/no-such-screen/at-all');
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });
  });
});
