/**
 * :module: AppComposition.test
 * :purpose: Verify the SPA's session composition with every application module real:
 *     the ``GET /session`` bootstrap that publishes the signed-on identity, the route
 *     guards that wait for it before deciding, the api client's ``401`` response
 *     interceptor that drops local authority and returns the operator to the sign-on
 *     screen (legacy ``RETURN-TO-SIGNON-SCREEN``), and the ``COMEN01C`` ``F3`` exit
 *     that revokes the server session through ``POST /logout`` before a signed-out
 *     screen is presented.
 * :output: Assertions only; the module exports nothing.
 * :note: Nothing in ``src`` is mocked. Only the TRANSPORT is replaced — the axios
 *     instance keeps both real interceptor chains and is given a stub adapter, the
 *     same technique ``api/client.test.ts`` uses. Every layer between the adapter and
 *     the DOM is the shipped one: ``api/auth``, ``api/menu``, ``api/accounts``, the
 *     ``hooks/useSession`` store, ``App``'s ``RequireAuth`` / ``RequireAdmin`` guards
 *     and the ``Layout`` shell. Removing the session bootstrap, the guards or the
 *     interceptor therefore fails this suite.
 * :note: The adapter models the server, not just the wire: a successful
 *     ``POST /logout`` clears the identity, so the next ``GET /session`` answers
 *     ``401`` exactly as a revoked Spring Session would.
 * :note: jsdom implements no navigation, so the browser path is parked on
 *     ``/signon`` — the one path for which the client's expiry handling skips
 *     ``location.assign``. The SPA's own route is driven independently by
 *     ``MemoryRouter``, which is where the redirect is asserted.
 */

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import type { ReactElement } from 'react';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import apiClient from './api/client';
import App from './App';
import { resolveSessionFromServer } from './testing/sessionHarness';
import type { MenuResponseDto, Role, SessionIdentityDto } from './types';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from './types';

/** Client route of the sign-on screen (``COSGN00`` / ``CC00``). */
const SIGNON_ROUTE = '/signon';

/** Client route of the main menu (``COMEN01`` / ``CM00``). */
const MAIN_MENU_ROUTE = '/menu';

/** Client route of the administrator menu (``COADM01`` / ``CA00``). */
const ADMIN_MENU_ROUTE = '/admin';

/** Account key the account-view deep link carries, at its ``PIC 9(11)`` width. */
const ACCOUNT_ID = '00000000011';

/** Signed-on standard user (``CDEMO-USER-ID``). */
const USER_ID = 'USER0001';

/** Line-24 legend of the ``COMEN01`` exit key. */
const PF3_LABEL = 'F3=Exit';

/** The one menu option the stubbed ``GET /menu`` serves. */
const MENU_RESPONSE: MenuResponseDto = {
  tranId: 'CM00',
  programName: 'COMEN01C',
  options: [
    {
      optionNumber: 1,
      optionName: 'Account View',
      programName: 'COACTVWC',
      targetRoute: '/accounts',
    },
  ],
  message: null,
};

/** One request the stub adapter observed. */
interface ObservedRequest {
  method: string;
  url: string;
}

/** Every request the adapter observed during the current test, oldest first. */
let observed: ObservedRequest[];

/**
 * The identity the modelled server holds, or ``null`` when it holds no session.
 * This is the single origin of every session in this suite: the store is never
 * written directly.
 */
let serverIdentity: SessionIdentityDto | null;

/** Status the modelled server answers a business read with. */
let accountReadStatus: number;

/**
 * :purpose: Build the rejection an axios adapter produces for an HTTP failure —
 *     a real ``Error`` carrying the request config and the response, which is what
 *     the client's response interceptor narrows.
 * :param status: the HTTP status code.
 * :param config: the originating request config.
 * :returns: the rejection value.
 */
function httpFailure(status: number, config: InternalAxiosRequestConfig): Error {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    isAxiosError: true,
    config,
    response: { status, data: undefined, headers: {} },
  });
}

/**
 * :purpose: Build the fulfilment an axios adapter produces for a success.
 * :param data: the response body.
 * :param config: the originating request config.
 * :returns: the resolved axios response.
 */
function httpSuccess(data: unknown, config: InternalAxiosRequestConfig): unknown {
  return { data, status: 200, statusText: 'OK', headers: {}, config };
}

/**
 * :purpose: Gate that holds the ``GET /session`` answer, installed only by the
 *     bootstrap test so the window before the server answers is observable.
 */
let sessionProbeGate: { readonly held: Promise<void>; readonly release: () => void } | null =
  null;

/**
 * :purpose: Hold the next ``GET /session`` answer until :func:`releaseSessionProbe`
 *     is called.
 */
function deferSessionProbe(): void {
  let release: () => void = () => undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  sessionProbeGate = { held, release };
}

/**
 * :purpose: Let a held ``GET /session`` answer through, and leave the server
 *     answering at once from then on. Safe to call when nothing is held, so a
 *     teardown can never leave a request parked.
 */
function releaseSessionProbe(): void {
  const gate = sessionProbeGate;
  sessionProbeGate = null;
  gate?.release();
}

/**
 * :purpose: The modelled CardDemo server. It answers the four routes this suite
 *     exercises and fails every other path, so an unexpected call is visible rather
 *     than silently satisfied.
 * :param config: the outgoing request config.
 * :returns: the adapter's promise.
 */
async function serve(config: InternalAxiosRequestConfig): Promise<unknown> {
  const method = (config.method ?? 'get').toLowerCase();
  const url = config.url ?? '';
  observed.push({ method, url });

  if (method === 'get' && url === '/session') {
    if (sessionProbeGate !== null) {
      await sessionProbeGate.held;
    }
    return serverIdentity === null
      ? Promise.reject(httpFailure(401, config))
      : httpSuccess(serverIdentity, config);
  }
  if (method === 'post' && url === '/logout') {
    // A revoked Spring Session stops answering the identity probe.
    serverIdentity = null;
    return httpSuccess(undefined, config);
  }
  if (method === 'get' && url === MAIN_MENU_ROUTE) {
    return httpSuccess(MENU_RESPONSE, config);
  }
  if (method === 'get' && url === `/accounts/${ACCOUNT_ID}`) {
    return accountReadStatus === 200
      ? httpSuccess({}, config)
      : Promise.reject(httpFailure(accountReadStatus, config));
  }
  return Promise.reject(httpFailure(404, config));
}

/**
 * :purpose: Report the SPA's current route so a guard redirect is observable.
 * :returns: the span carrying the active pathname.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

/**
 * :purpose: Mount the whole application at one history entry.
 * :param path: the initial route.
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
 * :purpose: Establish a session the way the application establishes one: the server
 *     publishes the identity and the production ``GET /session`` probe reads it.
 * :param userId: the user id the server reports.
 * :param role: the role the server reports.
 */
async function establishSession(userId: string, role: Role): Promise<void> {
  serverIdentity = { userId, userType: role };
  await resolveSessionFromServer();
}

/**
 * :purpose: Read the shell's authentication flag, the attribute the ``Layout`` frame
 *     publishes from the session store.
 * :returns: ``'true'`` / ``'false'``, or ``null`` when no frame is mounted.
 */
function authenticatedFlag(): string | null {
  return document.querySelector('.screen')?.getAttribute('data-authenticated') ?? null;
}

/**
 * :purpose: Assert that the screen publishing ``programName`` is on display. Every
 *     screen publishes its legacy program name into the shared shell, so the header
 *     value names the mounted screen unambiguously.
 * :param programName: the expected legacy program name.
 */
async function expectScreen(programName: string): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(programName);
  });
}

beforeEach(() => {
  observed = [];
  serverIdentity = null;
  accountReadStatus = 200;
  sessionProbeGate = null;
  // The browser path the client's expiry handling treats as "already there", so the
  // unimplemented jsdom navigation is never attempted.
  window.history.pushState({}, '', SIGNON_ROUTE);
  apiClient.defaults.adapter = serve as unknown as AxiosAdapter;
});

afterEach(async () => {
  // Nothing may stay parked in the adapter, or the teardown probe would never answer.
  releaseSessionProbe();
  // The mounted tree is released before the store is returned to signed out, so no
  // screen observes the teardown transition.
  cleanup();
  serverIdentity = null;
  await resolveSessionFromServer();
});

describe('App composition — session bootstrap over GET /session', () => {
  it('holds a guarded deep link until the server answers, then admits the published session', async () => {
    // Answering is deferred, so the window a guard must not decide in is observable.
    deferSessionProbe();
    serverIdentity = { userId: USER_ID, userType: CDEMO_USRTYP_USER };

    renderAt(MAIN_MENU_ROUTE);

    await waitFor(() => {
      expect(screen.getByTestId('session-resolving')).toBeInTheDocument();
    });
    // The guard has neither admitted nor bounced the deep link: no screen is mounted
    // in the frame's body, so no program name is published and no menu was fetched.
    expect(screen.getByTestId('pgm-name').textContent).toBe('');
    expect(observed.filter((request) => request.url === MAIN_MENU_ROUTE)).toHaveLength(0);
    expect(screen.getByTestId('location')).toHaveTextContent(MAIN_MENU_ROUTE);

    await act(async () => {
      releaseSessionProbe();
      await Promise.resolve();
    });

    await expectScreen('COMEN01C');
    expect(screen.getByTestId('location')).toHaveTextContent(MAIN_MENU_ROUTE);
    expect(observed.some((request) => request.url === '/session')).toBe(true);
  });

  it('returns a guarded deep link to the sign-on screen when the server holds no session', async () => {
    renderAt(MAIN_MENU_ROUTE);

    await expectScreen('COSGN00C');
    expect(screen.getByTestId('location')).toHaveTextContent(SIGNON_ROUTE);
    expect(observed.filter((request) => request.url === MAIN_MENU_ROUTE)).toHaveLength(0);
  });

  it('resolves the entry route to the screen the published role starts on', async () => {
    await establishSession('ADMIN001', CDEMO_USRTYP_ADMIN);

    renderAt('/');

    await expectScreen('COADM01C');
    expect(screen.getByTestId('location')).toHaveTextContent(ADMIN_MENU_ROUTE);
  });
});

describe('App composition — a 401 revokes the session in flight', () => {
  it('sends the operator back to the sign-on screen when a business read reports 401', async () => {
    await establishSession(USER_ID, CDEMO_USRTYP_USER);
    // The server has revoked the session: the business read and the identity probe
    // both refuse it.
    accountReadStatus = 401;
    serverIdentity = null;

    renderAt(`/accounts/${ACCOUNT_ID}`);

    await expectScreen('COSGN00C');
    expect(screen.getByTestId('location')).toHaveTextContent(SIGNON_ROUTE);
    expect(authenticatedFlag()).toBe('false');
  });

  it('leaves the session in place when a business read fails for a reason other than authorization', async () => {
    await establishSession(USER_ID, CDEMO_USRTYP_USER);
    accountReadStatus = 500;

    renderAt(`/accounts/${ACCOUNT_ID}`);

    await expectScreen('COACTVWC');
    expect(screen.getByTestId('location')).toHaveTextContent(`/accounts/${ACCOUNT_ID}`);
    expect(authenticatedFlag()).toBe('true');
  });
});

describe('App composition — F3 revokes the server session before signing out', () => {
  it('issues POST /logout and only then presents the sign-on screen', async () => {
    await establishSession(USER_ID, CDEMO_USRTYP_USER);

    renderAt(MAIN_MENU_ROUTE);
    await expectScreen('COMEN01C');

    fireEvent.click(screen.getByRole('button', { name: PF3_LABEL }));

    await expectScreen('COSGN00C');
    expect(observed).toEqual(
      expect.arrayContaining([{ method: 'post', url: '/logout' }]),
    );
    expect(screen.getByTestId('location')).toHaveTextContent(SIGNON_ROUTE);
    expect(authenticatedFlag()).toBe('false');
  });

  it('refuses a guarded route once the session has been revoked', async () => {
    await establishSession(USER_ID, CDEMO_USRTYP_USER);

    renderAt(MAIN_MENU_ROUTE);
    await expectScreen('COMEN01C');

    fireEvent.click(screen.getByRole('button', { name: PF3_LABEL }));
    await expectScreen('COSGN00C');

    cleanup();
    renderAt(MAIN_MENU_ROUTE);

    await expectScreen('COSGN00C');
    expect(screen.getByTestId('location')).toHaveTextContent(SIGNON_ROUTE);
  });
});
