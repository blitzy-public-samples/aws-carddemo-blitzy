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
 * :note: Only the TRANSPORT is replaced: ``./api/client`` is mocked through
 *     ``jest.unstable_mockModule`` so no axios instance, network request, or Vite
 *     ``import.meta`` evaluation occurs, while the real ``./api`` barrel, the real
 *     ``getSessionIdentity`` call and the real session store stay in the path under
 *     test. A session is therefore established the way the application establishes
 *     one — the stubbed ``GET /session`` publishes the identity and the production
 *     probe reads it — so removing the SPA's session bootstrap or its route guards
 *     fails these tests. ``App`` is imported dynamically after the mock is
 *     registered, sharing one React instance with the statically imported Testing
 *     Library.
 */

import { jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router';
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

/** Path the session store's identity probe issues (``getSessionIdentity``). */
const SESSION_PATH = '/session';

/**
 * Identity the stubbed ``GET /session`` publishes, or ``null`` for a caller the
 * server holds no session for. This is the only place a session originates: the
 * store is never written directly.
 */
let serverIdentity: { userId: string; userType: Role } | null = null;

/**
 * Serve the transport for a ``GET``. Only the session probe is answered; every
 * other read fails as a transport error so no page reaches a status-specific
 * branch.
 *
 * :param url: request path.
 * :returns: the stubbed axios response, or a rejection.
 */
const getRequest = (url: string): Promise<unknown> => {
  if (url !== SESSION_PATH) {
    return requestFailure();
  }
  return serverIdentity === null
    ? Promise.reject(new MockApiError(401, 'No session'))
    : Promise.resolve({ data: serverIdentity });
};

jest.unstable_mockModule('./api/client', () => ({
  __esModule: true,
  default: {
    get: getRequest,
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
  generateCorrelationId: (): string => 'cid-app-routing-double',
  registerSessionExpiryHandler: (): (() => void) => (): void => undefined,
}));

let App: () => ReactElement;
let resolveSessionFromServer: () => Promise<void>;

beforeAll(async () => {
  ({ default: App } = await import('./App'));
  ({ resolveSessionFromServer } = await import('./testing/sessionHarness'));
});

/**
 * Reports the current router location so a redirect can be asserted on the resolved
 * path, on the absence of any location state, and on the navigation type, so a
 * replace-only redirect chain can be told from one that pushes history entries.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <>
      <span data-testid="location">{`${location.pathname}${location.search}`}</span>
      <span data-testid="location-state">{JSON.stringify(location.state)}</span>
      <span data-testid="navigation-type">{navigationType}</span>
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
 * Establish an authenticated session before mounting, by having the server report
 * the identity to the production ``GET /session`` probe.
 *
 * :param user: user id the server publishes as ``CDEMO-USER-ID``.
 * :param role: role the server publishes as ``CDEMO-USER-TYPE``.
 */
async function signInAs(user: string, role: Role): Promise<void> {
  serverIdentity = { userId: user, userType: role };
  await resolveSessionFromServer();
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
  afterEach(async () => {
    serverIdentity = null;
    await resolveSessionFromServer();
  });

  it('serves the sign-on screen without a session', async () => {
    renderAt('/signon');
    await expectScreen('COSGN00C');
  });

  it('frames every screen in the shared terminal shell', async () => {
    await signInAs('USER0001', CDEMO_USRTYP_USER);
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

    it('resolves an unknown deep link to the sign-on screen', async () => {
      renderAt('/no-such-screen');
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });
  });

  describe('standard user', () => {
    it.each(USER_SCREENS)('mounts %s', async (path, programName) => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt(path);
      await expectScreen(programName);
      expect(screen.getByTestId('location')).toHaveTextContent(path);
    });

    it.each(ADMIN_SCREENS.map(([path]) => path))(
      'is refused %s and returns to the main menu',
      async (path) => {
        await signInAs('USER0001', CDEMO_USRTYP_USER);
        renderAt(path);
        await expectScreen('COMEN01C');
        expect(screen.getByTestId('location')).toHaveTextContent('/menu');
      },
    );

    it('resolves the entry route to the main menu', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/');
      // COSGN00C transfers a type 'U' operator to COMEN01C, so '/' resolves to /menu.
      await expectScreen('COMEN01C');
      expect(screen.getByTestId('location')).toHaveTextContent('/menu');
    });
  });

  describe('administrator', () => {
    it.each(ADMIN_SCREENS)('mounts %s', async (path, programName) => {
      await signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt(path);
      await expectScreen(programName);
      expect(screen.getByTestId('location')).toHaveTextContent(path);
    });

    it.each(USER_SCREENS)('also mounts the shared screen %s', async (path, programName) => {
      await signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt(path);
      await expectScreen(programName);
    });

    it('resolves the entry route to the administrator menu', async () => {
      await signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt('/');
      // COSGN00C transfers a type 'A' operator to COADM01C, so '/' resolves to /admin.
      await expectScreen('COADM01C');
      expect(screen.getByTestId('location')).toHaveTextContent('/admin');
    });
  });

  describe('literal path segments outrank route parameters', () => {
    it('keeps the card filter empty on the card-detail entry screen', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/cards/view');
      await expectScreen('COCRDSLC');
      expect(screen.getByTestId('cardsid')).toHaveValue('');
    });

    it('routes no card number in a path segment', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/cards/4111111111111111');
      // The selected card travels in the router location state, never in a path
      // segment, so no route claims one: the path falls to the catch-all and
      // re-enters at the role's own menu, carrying no card number forward.
      await expectScreen('COMEN01C');
      expect(screen.getByTestId('location')).toHaveTextContent('/menu');
    });

    it('keeps the account key empty on the account-view entry screen', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/accounts');
      await expectScreen('COACTVWC');
      expect(screen.getByLabelText(/Account Number/i)).toHaveValue('');
    });

    it('reaches the account-update screen, not the account view, at /accounts/update', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/accounts/update');
      await expectScreen('COACTUPC');
    });

    it('reaches the add-transaction screen, not the transaction view, at /transactions/add', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/transactions/add');
      await expectScreen('COTRN02C');
    });

    it('reaches the card-update screen, not the card detail, at /cards/update', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/cards/update');
      await expectScreen('COCRDUPC');
    });
  });

  describe('unknown deep link', () => {
    it('returns a standard user to the main menu', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/no-such-screen');
      // The 3270 had no not-found state: an unrecognised transaction returned the
      // operator to a menu, which the role-resolved entry route reproduces.
      await expectScreen('COMEN01C');
      expect(screen.getByTestId('location')).toHaveTextContent('/menu');
    });

    it('returns an administrator to the administrator menu', async () => {
      await signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderAt('/no-such-screen');
      await expectScreen('COADM01C');
      expect(screen.getByTestId('location')).toHaveTextContent('/admin');
    });

    it('sends a visitor without a session to the sign-on screen', async () => {
      renderAt('/no-such-screen/at-all');
      await expectScreen('COSGN00C');
      expect(screen.getByTestId('location')).toHaveTextContent('/signon');
    });

    it('leaves no intermediate entry in history on either hop', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderAt('/no-such-screen');
      await expectScreen('COMEN01C');
      // Both the catch-all and the entry route redirect with `replace`, so neither the
      // unknown path nor '/' is pushed onto the session history.
      expect(screen.getByTestId('navigation-type')).toHaveTextContent('REPLACE');
    });
  });
});
