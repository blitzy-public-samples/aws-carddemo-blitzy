/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

/**
 * :purpose: Mount every screen the way production mounts it — inside
 *     ``<StrictMode>`` — and prove each screen's entry effects are IDEMPOTENT.
 *     ``frontend/src/main.tsx`` wraps the application in ``React.StrictMode``, so
 *     React double-invokes every component body and mounts, unmounts and remounts
 *     every effect. The per-screen suites render a single-invocation tree, which
 *     cannot observe a non-idempotent effect: a read whose URL drifts between the
 *     two passes, a write issued from a mount effect, a subscription that survives
 *     the intermediate unmount, or a screen that fails to paint the second time.
 *     This suite renders the REAL ``App`` (real routes, real guards, real session
 *     store, real ``./api`` barrel) with Testing Library's ``reactStrictMode``
 *     render option and asserts those four properties for all nineteen routes.
 * :output: Jest assertions only. The axios client is the single mocked seam, so no
 *     request leaves the test process; every non-session read fails as a transport
 *     error (status ``0``) so no screen reaches a status-specific branch such as the
 *     ``401`` sign-on redirect or the ``409`` optimistic-lock conflict.
 */

import { jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { ReactElement } from 'react';
import type { Role } from './types/session';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from './types/session';

/**
 * Stand-in for the real ``ApiError`` carrying the fields ``useApi`` narrows on.
 *
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: resolved, human-readable message.
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

/** Path the session store's identity probe issues (``getSessionIdentity``). */
const SESSION_PATH = '/session';

/** Identity the stubbed ``GET /session`` publishes, or ``null`` for no session. */
let serverIdentity: { userId: string; userType: Role } | null = null;

/** Every ``GET`` path the mounted tree issued, session probe included. */
let getPaths: string[] = [];

/** Every mutating request the mounted tree issued, as ``VERB path``. */
let mutations: string[] = [];

/**
 * Serve a ``GET``. Only the session probe is answered; every other read fails as a
 * transport error, which is enough to exercise the entry effect and record its URL.
 *
 * :param url: request path.
 * :returns: the stubbed axios response, or a rejection.
 */
const getRequest = (url: string): Promise<unknown> => {
  getPaths.push(url);
  if (url !== SESSION_PATH) {
    return Promise.reject(new MockApiError(0, 'Request suppressed in test'));
  }
  return serverIdentity === null
    ? Promise.reject(new MockApiError(401, 'No session'))
    : Promise.resolve({ data: serverIdentity });
};

/**
 * Record and refuse a mutating request. A screen must not issue one from a mount
 * effect, so a recorded entry here is the failure this suite exists to catch.
 *
 * :param verb: HTTP verb.
 * :returns: a rejecting request function.
 */
const mutatingRequest =
  (verb: string) =>
  (url: string): Promise<never> => {
    mutations.push(`${verb} ${url}`);
    return Promise.reject(new MockApiError(0, 'Request suppressed in test'));
  };

jest.unstable_mockModule('./api/client', () => ({
  __esModule: true,
  default: {
    get: getRequest,
    post: mutatingRequest('POST'),
    put: mutatingRequest('PUT'),
    delete: mutatingRequest('DELETE'),
    interceptors: {
      request: { use: () => 0 },
      response: { use: () => 0 },
    },
  },
  ApiError: MockApiError,
  isApiError: (value: unknown): boolean => value instanceof MockApiError,
  // The real module publishes the cancellation seam the request hook uses: `run` issues
  // every call inside `runWithRequestSignal` so the signal reaches the transport, and a
  // cancelled outcome is recognised through `isCancelledRequest` rather than by inspecting
  // axios internals. Both are stubbed here — nothing in this suite cancels — but they must
  // EXIST, or importing `useApi` fails on the missing export before a test runs.
  isCancelledRequest: (): boolean => false,
  runWithRequestSignal: <T,>(_signal: AbortSignal, call: () => T): T => call(),
  generateCorrelationId: (): string => 'cid-strict-mode-mount',
  registerSessionExpiryHandler: (): (() => void) => (): void => undefined,
}));

let App: () => ReactElement;
let resolveSessionFromServer: () => Promise<void>;

beforeAll(async () => {
  ({ default: App } = await import('./App'));
  ({ resolveSessionFromServer } = await import('./testing/sessionHarness'));
});

beforeEach(() => {
  getPaths = [];
  mutations = [];
});

/**
 * Mount the application at one history entry, under ``<StrictMode>``.
 *
 * :param path: initial route.
 * :returns: the render result, so the caller can unmount and assert on cleanup.
 */
function renderStrictAt(path: string): ReturnType<typeof render> {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
    // Overrides the global default: this suite alone renders the way main.tsx does.
    { reactStrictMode: true },
  );
}

/**
 * Establish an authenticated session before mounting, by having the server report the
 * identity to the production ``GET /session`` probe.
 *
 * :param user: user id the server publishes as ``CDEMO-USER-ID``.
 * :param role: role the server publishes as ``CDEMO-USER-TYPE``.
 */
async function signInAs(user: string, role: Role): Promise<void> {
  serverIdentity = { userId: user, userType: role };
  await resolveSessionFromServer();
}

/**
 * Assert the screen whose chrome carries ``programName`` is on display. Every page
 * publishes its legacy program name into the shared shell, so the header value
 * identifies the mounted screen unambiguously — and under StrictMode it also proves
 * the chrome the page republishes on the SECOND mount reaches the shell.
 *
 * :param programName: expected legacy program name.
 */
async function expectScreen(programName: string): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(programName);
  });
}

/**
 * Wait until the screen's entry read has SETTLED, so a snapshot describes the terminal
 * state rather than the keyboard-locked one.
 *
 * :note: While a read is outstanding the shell paints its 3270 operator-information
 *     affordance (`X SYSTEM`) and withholds the message the read will publish. Both
 *     renders below must be compared in the same state, and the settled one is the only
 *     deterministic choice.
 */
async function expectSettled(): Promise<void> {
  await waitFor(() => {
    expect(document.querySelector('[aria-busy="true"]')).toBeNull();
  });
}

/** The reads the mounted screen issued, with the session probe filtered out. */
function screenReads(): string[] {
  return getPaths.filter((path) => path !== SESSION_PATH);
}

/**
 * Route, legacy program name, and the ONE path the screen reads on entry -- ``null``
 * for the entry-form screens, which read nothing until the operator supplies a key
 * (``COACTVW``/``COACTUP`` without a routed account, ``COCRDSL``/``COCRDUP``,
 * ``COTRN01``/``COTRN02``, ``COBIL00``, ``CORPT00``, ``COUSR01``-``COUSR03``).
 */
type ScreenCase = readonly [path: string, programName: string, entryRead: string | null];

/** Every screen reachable by a standard user. */
const USER_SCREENS: ReadonlyArray<ScreenCase> = [
  ['/menu', 'COMEN01C', '/menu'],
  ['/accounts', 'COACTVWC', null],
  ['/accounts/00000000011', 'COACTVWC', '/accounts/00000000011'],
  ['/accounts/update', 'COACTUPC', null],
  ['/accounts/00000000011/update', 'COACTUPC', '/accounts/00000000011'],
  ['/cards', 'COCRDLIC', '/cards'],
  ['/cards/view', 'COCRDSLC', null],
  ['/cards/update', 'COCRDUPC', null],
  ['/transactions', 'COTRN00C', '/transactions'],
  ['/transactions/view', 'COTRN01C', null],
  // A routed transaction id is read through `GET /transactions/detail?tranId=`, not as a
  // path segment: a 16-digit segment survived nginx's URI normalization as `/..`, which
  // lifted the request out of the `/api` prefix and answered it with the SPA document.
  ['/transactions/0000000000000001', 'COTRN01C', '/transactions/detail'],
  ['/transactions/add', 'COTRN02C', null],
  ['/billpay', 'COBIL00C', null],
  ['/reports', 'CORPT00C', null],
];

/** Every administrator-only screen. */
const ADMIN_SCREENS: ReadonlyArray<ScreenCase> = [
  ['/admin', 'COADM01C', '/admin/menu'],
  ['/users', 'COUSR00C', '/users'],
  ['/users/add', 'COUSR01C', null],
  ['/users/update', 'COUSR02C', null],
  ['/users/delete', 'COUSR03C', null],
];

/**
 * The exact read log a screen must produce under StrictMode: nothing at all for an
 * entry-form screen, and otherwise the SAME path twice -- once per effect invocation.
 * Asserting the doubled entry is what proves StrictMode is genuinely engaged; a single
 * entry would mean the render option was ignored and the suite proved nothing.
 *
 * :param entryRead: the path the screen reads on entry, or ``null`` when it reads none.
 * :returns: the expected read log.
 */
function expectedReads(entryRead: string | null): string[] {
  return entryRead === null ? [] : [entryRead, entryRead];
}

/**
 * :purpose: Replace the header clock with a fixed token so two renders taken moments
 *     apart compare on the screen they painted rather than on the second they were
 *     painted in. ``Header`` renders ``Time:`` from the wall clock (``EIBTIME``), so a
 *     comparison that crosses a second boundary would otherwise fail on that alone.
 * :param text: the rendered screen text.
 * :returns: the same text with the ``Time: HH:MM:SS`` value masked.
 */
function withoutClock(text: string): string {
  return text.replace(/Time: \d{2}:\d{2}:\d{2}/, 'Time: HH:MM:SS');
}

describe('StrictMode double mount — every screen', () => {
  afterEach(async () => {
    serverIdentity = null;
    await resolveSessionFromServer();
  });

  describe('standard-user screens', () => {
    it.each(USER_SCREENS)(
      'paints %s and repeats its entry read without drift',
      async (path, programName, entryRead) => {
        await signInAs('USER0001', CDEMO_USRTYP_USER);
        renderStrictAt(path);
        await expectScreen(programName);

        // StrictMode runs the entry effect twice, so the read log is the SAME path
        // twice -- no drift between the passes, no third read, and nothing at all for
        // a screen that waits for a key. A single entry would mean StrictMode was not
        // engaged at all.
        expect(screenReads()).toEqual(expectedReads(entryRead));
        // No screen may write from a mount effect: the double invocation would then
        // post, put or delete twice on entry.
        expect(mutations).toEqual([]);
      },
    );
  });

  describe('administrator screens', () => {
    it.each(ADMIN_SCREENS)(
      'paints %s and repeats its entry read without drift',
      async (path, programName, entryRead) => {
        await signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
        renderStrictAt(path);
        await expectScreen(programName);

        expect(screenReads()).toEqual(expectedReads(entryRead));
        expect(mutations).toEqual([]);
      },
    );
  });

  describe('sign-on screen and the session bootstrap', () => {
    it('paints the sign-on screen for a visitor with no session', async () => {
      renderStrictAt('/signon');
      await expectScreen('COSGN00C');
      // COSGN00 reads nothing on entry; the only GET is the session probe, and the
      // screen submits nothing until the operator presses ENTER.
      expect(screenReads()).toEqual([]);
      expect(mutations).toEqual([]);
    });

    it('resolves the role-gated entry route once for an administrator', async () => {
      await signInAs('ADMIN001', CDEMO_USRTYP_ADMIN);
      renderStrictAt('/');
      // COSGN00C transfers a type 'A' operator to COADM01C, so '/' resolves to /admin
      // and must still resolve there when the guard's effects run twice.
      await expectScreen('COADM01C');
      expect(mutations).toEqual([]);
    });

    it('resolves the role-gated entry route once for a standard user', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      renderStrictAt('/');
      await expectScreen('COMEN01C');
      expect(mutations).toEqual([]);
    });
  });

  describe('effect cleanup is symmetric', () => {
    it('issues no further request after the tree is unmounted', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);
      const view = renderStrictAt('/cards');
      await expectScreen('COCRDLIC');

      const readsWhileMounted = getPaths.length;
      view.unmount();
      // A subscription that survived StrictMode's intermediate unmount would keep
      // reading after the real unmount; the recorded request log must stop dead.
      await Promise.resolve();
      expect(getPaths).toHaveLength(readsWhileMounted);
      expect(mutations).toEqual([]);
    });
  });

  describe('the double mount paints the same screen as a single mount', () => {
    it('renders identical screen text with and without StrictMode', async () => {
      await signInAs('USER0001', CDEMO_USRTYP_USER);

      const single = render(
        <MemoryRouter initialEntries={['/menu']}>
          <App />
        </MemoryRouter>,
        { reactStrictMode: false },
      );
      await expectScreen('COMEN01C');
      await expectSettled();
      const singleText = single.container.querySelector('.screen')?.textContent ?? '';
      single.unmount();

      renderStrictAt('/menu');
      await expectScreen('COMEN01C');
      await expectSettled();
      const strictText =
        document.querySelector('.screen')?.textContent ?? '';

      expect(singleText).not.toBe('');
      expect(withoutClock(strictText)).toBe(withoutClock(singleText));
    });
  });
});
