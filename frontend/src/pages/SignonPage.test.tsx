/**
 * :module: SignonPage.test
 * :purpose: Workflow-parity suite for the CardDemo sign-on screen, the React
 *     replacement for BMS mapset ``COSGN00`` (map ``COSGN0A``) driven by CICS
 *     transaction ``CC00`` / program ``COSGN00C``. Locks the frozen map contract
 *     (the instructional prompt, the two 8-character entry fields, the masked
 *     password field, the ``(8 Char)`` width hints and the line-24
 *     ``ENTER=Sign-on`` / ``F3=Exit`` legend), the verbatim ``PROCESS-ENTER-KEY``
 *     and ``READ-USER-SEC-FILE`` message texts, the role-based routing that
 *     replaces the ``XCTL`` to ``COADM01C`` / ``COMEN01C``, the ``DFHENTER`` /
 *     ``DFHPF3`` AID wiring, and the confidentiality of the entered password.
 * :output: Jest assertions only; the suite produces no application output.
 * :note: ``../api`` is replaced by a module mock, so neither axios nor the Vite
 *     ``import.meta`` read in ``api/config.ts`` is ever evaluated. The mock keeps
 *     an ``ApiError`` stand-in carrying ``status``, ``body`` and
 *     ``isOptimisticLockConflict``, so the page's ``instanceof`` narrowing holds.
 *     ``useSession`` reaches the network only through ``signon``, so mocking
 *     ``../api`` intercepts the whole sign-in path. Under Jest's native-ESM
 *     runtime the registration is ``jest.unstable_mockModule`` and every module
 *     that transitively reaches ``../api`` — the page, the ``Layout`` chrome
 *     provider and the ``../hooks`` barrel — is imported dynamically after it, as
 *     in ``hooks/useSession.test.ts``. No module registry reset is performed, so
 *     those modules share one React instance with Testing Library.
 * :note: The page publishes its line-23 message region and line-24 legend as
 *     screen chrome instead of rendering them itself, so it is mounted inside the
 *     shared ``Layout`` (the provider that renders that chrome) within a
 *     ``MemoryRouter`` whose sibling routes act as navigation probes.
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
// ``CCDA-MSG-THANK-YOU`` verbatim, the one line PF3 leaves on the erased screen.
import { CCDA_MSG_THANK_YOU } from '../types';
import type { RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
// Jest's native-ESM runtime does not inject ``jest`` as a global (unlike
// ``describe`` / ``it`` / ``expect``), so it is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { ReactElement } from 'react';
import { CDEMO_USRTYP_ADMIN, CDEMO_USRTYP_USER } from '../types';
import type {
  ApiErrorResponse,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';

/**
 * :purpose: Stand-in for the client-normalized ``ApiError``, keeping the real
 *     constructor shape and members so ``instanceof`` narrowing and the
 *     ``isOptimisticLockConflict`` flag behave as they do in production.
 * :param status: HTTP status of the failed call.
 * :param message: Normalized error message.
 * :param body: Backend error payload, when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the HTTP ``409`` conflict.
 */
class MockApiError extends Error {
  readonly status: number;

  readonly body?: ApiErrorResponse;

  readonly isOptimisticLockConflict: boolean;

  constructor(
    status: number,
    message: string,
    body?: ApiErrorResponse,
    isOptimisticLockConflict = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
  }
}

/** The mocked ``POST /auth/signon`` call reached through ``useSession.signIn``. */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

/**
 * Captures the expiry callback the session store registers, so a test can end the
 * session the way the server does — a ``401`` on any request — and assert what the
 * sign-on screen the operator lands on then reports.
 */
let expiryHandler: ((reason: 'expired' | 'refused') => void) | undefined;

jest.unstable_mockModule('../api', () => ({
  // The request-cancellation contract ``useApi`` binds to: the real scope hands the
  // caller's AbortSignal to axios, and the double simply invokes the call.
  runWithRequestSignal: (_signal: AbortSignal, call: () => unknown): unknown => call(),
  isCancelledRequest: (): boolean => false,
  // The session store this screen's module graph loads binds to these barrel
  // exports as well. ``signIn`` asks the server whether a session is already live
  // before it sends credentials, so the probe must answer: it reports that there
  // is none, which is the state the sign-on screen is always reached in.
  getSessionIdentity: jest.fn(() => Promise.reject(new Error('No session'))),
  logout: jest.fn(() => Promise.resolve(undefined)),
  registerSessionExpiryHandler: jest.fn(
    (handler: (reason: 'expired' | 'refused') => void) => {
      // Captured so a test can fire the centralized expiry path exactly as the axios
      // response interceptor does on a ``401``.
      expiryHandler = handler;
      return () => {
        expiryHandler = undefined;
      };
    },
  ),
  getAppId: jest.fn(() => 'CICS'),
  getSysId: jest.fn(() => 'CDMO'),
  __esModule: true,
  signon: signonMock,
  ApiError: MockApiError,
  isApiError: (err: unknown): boolean => err instanceof MockApiError,
}));

/** The sign-on screen under test. */
let SignonPage: (typeof import('./SignonPage'))['default'];

/** The shared screen shell that renders the chrome the page publishes. */
let Layout: (typeof import('../components/Layout'))['default'];

/** The client-normalized error type the page narrows failures against. */
let ApiError: (typeof import('../api'))['ApiError'];

/** The shared session harness, which seeds identity over ``GET /session``. */
type SessionHarness = typeof import('../testing/sessionHarness');

/** Establishes a signed-on session so the screen's exit path can be exercised. */
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];

/** Places the SPA in the server-confirmed signed-out state the screen is reached in. */
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

/** Withdraws a published session-ended notice, so it cannot leak between tests. */
let withdrawSessionNotice: SessionHarness['withdrawSessionNotice'];

/**
 * The row-23 copy an expired session raises, read from the session module so the test
 * asserts against the shipped literal rather than a duplicate of it.
 */
let SESSION_ENDED_MESSAGE: string;

beforeAll(async () => {
  // Imported after the mock registration so the page, the chrome provider and the
  // session store all bind to the mocked ``signon``.
  ({ default: Layout } = await import('../components/Layout'));
  ({ default: SignonPage } = await import('./SignonPage'));
  ({ ApiError } = await import('../api'));
  ({ seedSignedOnSession, seedSignedOutSession, withdrawSessionNotice } = await import(
    '../testing/sessionHarness'
  ));
  ({ SESSION_ENDED_MESSAGE } = await import('../hooks/useSession'));
});

/** Verbatim BMS ``POS=(17,16)`` instructional prompt of mapset ``COSGN00``. */
const PROMPT_TEXT = 'Type your User ID and Password, then press ENTER:';

/** Verbatim BMS ``POS=(19,52)`` / ``(20,52)`` field-width hint. */
const FIELD_WIDTH_HINT = '(8 Char)';

/** Frozen width of ``USERIDI PIC X(8)`` and ``PASSWDI PIC X(8)``. */
const FIELD_MAX_LENGTH = 8;

/** ENTER half of the BMS line-24 legend ``ENTER=Sign-on  F3=Exit``. */
const PF_ENTER_LABEL = 'ENTER=Sign-on';

/** PF3 half of the BMS line-24 legend ``ENTER=Sign-on  F3=Exit``. */
const PF_EXIT_LABEL = 'F3=Exit';

/** Accessible name of the page's submit control. */
const SUBMIT_LABEL = 'Sign-on';

/** CICS transaction id of the sign-on screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CC00';

/** Legacy program name shown after ``Prog :`` (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COSGN00C';

/** First header title line (``CCDA-TITLE01``). */
const TITLE01 = 'AWS Mainframe Modernization';

/** Second header title line (``CCDA-TITLE02``). */
const TITLE02 = 'CardDemo';

/** Public sign-on route and the ``F3`` exit target. */
const SIGNON_ROUTE = '/signon';

/** Administrator menu route, replacing the ``XCTL`` to ``COADM01C``. */
const ADMIN_MENU_ROUTE = '/admin';

/** Standard-user menu route, replacing the ``XCTL`` to ``COMEN01C``. */
const MAIN_MENU_ROUTE = '/menu';

/** Blank user-id message, verbatim ``COSGN00C`` ``PROCESS-ENTER-KEY``. */
const MSG_ENTER_USER_ID = 'Please enter User ID ...';

/** Blank password message, verbatim ``COSGN00C`` ``PROCESS-ENTER-KEY``. */
const MSG_ENTER_PASSWORD = 'Please enter Password ...';

/** Rejected-credential message, verbatim ``COSGN00C`` ``READ-USER-SEC-FILE``. */
const MSG_WRONG_PASSWORD = 'Wrong Password. Try again ...';

/** Unknown-user message, verbatim ``COSGN00C`` ``READ-USER-SEC-FILE`` RESP 13. */
const MSG_USER_NOT_FOUND = 'User not found. Try again ...';

/** ``WHEN OTHER`` message, verbatim ``COSGN00C`` ``READ-USER-SEC-FILE``. */
const MSG_UNABLE_TO_VERIFY = 'Unable to verify the User ...';

/** HTTP status the api layer raises for a rejected credential. */
const HTTP_UNAUTHORIZED = 401;

/** ``sessionStorage`` key under which ``useSession`` persists the session. */
const STORAGE_KEY = 'carddemo.session';

/** 8-character administrator user id from the ``USRSEC`` fixtures. */
const ADMIN_USER_ID = 'ADMIN001';

/** 8-character standard-user id from the ``USRSEC`` fixtures. */
const STANDARD_USER_ID = 'USER0001';

/** Mixed-case entry exercising the ``UPPER-CASE`` move on the user-id field. */
const MIXED_CASE_USER_ID = 'admin001';

/** Over-length user-id entry, truncated by the 8-character field bound. */
const OVERSIZED_USER_ID = 'ADMIN0019999';

/** Non-secret mixed-case test password; the case proves verbatim transmission. */
const TEST_PASSWORD = 'fakePw01';

/** Over-length password entry, truncated by the 8-character field bound. */
const OVERSIZED_PASSWORD = 'fakePw019999';

/** Administrator sign-on result routing to the administrator menu. */
const adminSignonResponse: SignonResponseDto = {
  userId: ADMIN_USER_ID,
  userType: CDEMO_USRTYP_ADMIN,
  redirectTarget: 'CA00',
};

/** Standard-user sign-on result routing to the standard-user menu. */
const standardSignonResponse: SignonResponseDto = {
  userId: STANDARD_USER_ID,
  userType: CDEMO_USRTYP_USER,
  redirectTarget: 'CM00',
};

/**
 * :purpose: Build the backend error payload carried by a rejected sign-on.
 * :param message: The verbatim legacy text the backend returns.
 * :returns: An ``ApiErrorResponse`` shaped like the gateway's error body.
 */
function unauthorizedBody(message: string): ApiErrorResponse {
  return {
    timestamp: '2026-08-05T12:00:00.000000',
    status: HTTP_UNAUTHORIZED,
    error: 'Unauthorized',
    message,
    path: '/auth/signon',
  };
}

/**
 * :purpose: Build the ``401`` ``ApiError`` the api client raises for a rejected
 *     credential, carrying the backend message in its body.
 * :param message: The verbatim legacy text the backend returns.
 * :returns: The ``ApiError`` to reject the mocked ``signon`` with.
 */
function unauthorizedError(message: string): Error {
  return new ApiError(HTTP_UNAUTHORIZED, message, unauthorizedBody(message));
}

/**
 * :purpose: Report the router's current path so navigation is observable.
 * :returns: A probe element rendering the active pathname.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

/**
 * :purpose: Mount the sign-on screen on its public route inside the shared
 *     ``Layout`` chrome provider, with probe routes standing in for the
 *     administrator and standard-user menus.
 * :returns: The Testing Library render result.
 */
function renderSignonPage(): RenderResult {
  return render(
    <MemoryRouter initialEntries={[SIGNON_ROUTE]}>
      <LocationProbe />
      <Layout>
        <Routes>
          <Route path={SIGNON_ROUTE} element={<SignonPage />} />
          <Route path={ADMIN_MENU_ROUTE} element={<div data-testid="admin-menu" />} />
          <Route path={MAIN_MENU_ROUTE} element={<div data-testid="main-menu" />} />
        </Routes>
      </Layout>
    </MemoryRouter>,
  );
}

/**
 * :purpose: The ``USERID`` entry field of the map.
 * :returns: The user-id input element.
 */
function userIdField(): HTMLInputElement {
  // The caption is the mapset literal ``'User ID     :'``; the accessible-name
  // query collapses its column padding.
  return screen.getByLabelText<HTMLInputElement>('User ID :');
}

/**
 * :purpose: The ``PASSWD`` entry field of the map.
 * :returns: The password input element.
 */
function passwordField(): HTMLInputElement {
  // The caption is the mapset literal ``'Password    :'``; the accessible-name
  // query collapses its column padding.
  return screen.getByLabelText<HTMLInputElement>('Password :');
}

/**
 * :purpose: Fill both entry fields and send the screen's ENTER AID, which is the
 *     line-24 ``ENTER=Sign-on`` key -- the only submit affordance the mapset declares.
 * :param userId: The user id to enter into the ``USERID`` field.
 * :param password: The password to enter into the ``PASSWD`` field.
 */
async function submitCredentials(userId: string, password: string): Promise<void> {
  const user = userEvent.setup();
  await user.type(userIdField(), userId);
  await user.type(passwordField(), password);
  // The screen body carries no control: ENTER is the AID, published on line 24 as
  // ``ENTER=Sign-on``.
  await user.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));
}

/**
 * :purpose: The router path currently rendered by :func:`LocationProbe`.
 * :returns: The active pathname.
 */
function currentPath(): string {
  return screen.getByTestId('location').textContent ?? '';
}

/**
 * :purpose: The text of the line-23 message region (``ERRMSG PIC X(78)``).
 * :returns: The message text, or an empty string when no message is displayed.
 */
function messageText(): string {
  return screen.queryByRole('alert')?.textContent ?? '';
}

/**
 * :purpose: The rendered ``data-authenticated`` flag of the screen frame.
 * :param view: The render result whose container holds the frame.
 * :returns: ``'true'`` when a session is established, otherwise ``'false'``.
 */
function authenticatedFlag(view: RenderResult): string | null {
  return view.container.querySelector('.screen')?.getAttribute('data-authenticated') ?? null;
}

/**
 * :purpose: Press the physical ENTER AID (``DFHENTER``) handled by the line-24 legend.
 * :note: Awaited inside ``act`` because the legend handler calls the page's ASYNC submit:
 *  the validation state it sets lands in a promise continuation, and the chrome the page
 *  republishes from it (``SignonPage`` effect -> ``Layout.setChrome``) is committed in a
 *  passive effect after that continuation. A bare ``fireEvent`` opens only a synchronous
 *  ``act`` scope, so both would settle outside it.
 */
async function pressEnterKey(): Promise<void> {
  await act(async () => {
    fireEvent.keyDown(document, { key: 'Enter' });
    // Awaited inside the SAME scope, so the promise continuation the handler queues and
    // the passive effect that republishes the screen chrome are both owned by it.
    await Promise.resolve();
  });
}

/**
 * :purpose: Press the physical PF3 AID (``DFHPF3``) handled by the line-24 legend.
 * :note: Awaited inside ``act`` for the same reason as :func:`pressEnterKey`.
 */
async function pressPf3Key(): Promise<void> {
  await act(async () => {
    fireEvent.keyDown(document, { key: 'F3' });
    await Promise.resolve();
  });
}

/**
 * :purpose: Flatten the recorded arguments of a console spy into one string.
 * :param calls: The spy's recorded argument lists.
 * :returns: Every argument rendered and joined, or an empty string.
 */
function renderCalls(calls: readonly unknown[][]): string {
  return calls.map((call) => call.map((arg) => String(arg)).join(' ')).join('\n');
}

beforeEach(async () => {
  signonMock.mockReset();
  sessionStorage.clear();
  await seedSignedOutSession();
  // The session store outlives an individual test, so a notice raised by one must not
  // still be standing on the next test's line-23 region.
  withdrawSessionNotice();
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('SignonPage — COSGN00 map rendering', () => {
  it('renders the instructional prompt verbatim', () => {
    renderSignonPage();

    expect(screen.getByText(PROMPT_TEXT)).toBeInTheDocument();
  });

  it('renders the User ID field as an 8-character unmasked entry field', () => {
    renderSignonPage();

    const field = userIdField();
    expect(field).toBeInTheDocument();
    expect(field).toHaveAttribute('type', 'text');
    expect(field).toHaveAttribute('maxlength', String(FIELD_MAX_LENGTH));
    expect(field).toHaveValue('');
  });

  it('renders the Password field as an 8-character masked entry field', () => {
    renderSignonPage();

    const field = passwordField();
    expect(field).toBeInTheDocument();
    expect(field).toHaveAttribute('type', 'password');
    expect(field).toHaveAttribute('maxlength', String(FIELD_MAX_LENGTH));
    expect(field).toHaveValue('');
  });

  it('renders the (8 Char) width hint beside both entry fields', () => {
    renderSignonPage();

    expect(screen.getAllByText(FIELD_WIDTH_HINT)).toHaveLength(2);
  });

  it('renders the line-24 legend as the ONLY submit affordance, none in the body', () => {
    renderSignonPage();

    const legend = screen.getByRole('group', { name: 'Function keys' });
    const enterKey = screen.getByRole('button', { name: PF_ENTER_LABEL });
    const exitKey = screen.getByRole('button', { name: PF_EXIT_LABEL });

    // `COSGN00.bms` declares two entry fields and one row-24 legend field. A `Sign-on`
    // button beside the fields was observable output the mapset does not declare and a
    // duplicate of the key already on line 24. The form's hidden submit control is not
    // exposed to the accessibility tree, so it is not one of these.
    expect(screen.queryByRole('button', { name: SUBMIT_LABEL })).toBeNull();
    expect(
      within(screen.getByRole('main')).queryAllByRole('button'),
    ).toHaveLength(0);
    expect(legend).toContainElement(enterKey);
    expect(legend).toContainElement(exitKey);
    expect(enterKey).toBeEnabled();
    expect(exitKey).toBeEnabled();
    // ``COSGN00.bms`` declares one line-24 legend and nothing else the operator can
    // activate, so the two legend keys are the only controls the screen offers and the
    // body's implicit-submit control is not one of them.
    expect(screen.getAllByRole('button')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: SUBMIT_LABEL })).toBeNull();
  });

  it('publishes the transaction id, program name, and titles as screen chrome', () => {
    renderSignonPage();

    expect(screen.getByTestId('tran-id')).toHaveTextContent(TRANSACTION_ID);
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(PROGRAM_NAME);
    expect(screen.getByTestId('title01')).toHaveTextContent(TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(TITLE02);
  });

  it('starts on the public sign-on route with an empty message region', () => {
    const view = renderSignonPage();

    expect(currentPath()).toBe(SIGNON_ROUTE);
    expect(messageText()).toBe('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(authenticatedFlag(view)).toBe('false');
  });
});

describe('SignonPage — entry-field behavior', () => {
  it('upper-cases the user id as it is typed', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), MIXED_CASE_USER_ID);

    expect(userIdField()).toHaveValue(ADMIN_USER_ID);
  });

  it('bounds the user id to the 8-character USERIDI width', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), OVERSIZED_USER_ID);

    expect(userIdField().value).toHaveLength(FIELD_MAX_LENGTH);
    expect(userIdField()).toHaveValue(ADMIN_USER_ID);
  });

  it('bounds the password to the 8-character PASSWDI width', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(passwordField(), OVERSIZED_PASSWORD);

    expect(passwordField().value).toHaveLength(FIELD_MAX_LENGTH);
    expect(passwordField()).toHaveValue(TEST_PASSWORD);
  });

  it('keeps the password exactly as entered, without case folding', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(passwordField(), TEST_PASSWORD);

    expect(passwordField()).toHaveValue(TEST_PASSWORD);
  });
});

describe('SignonPage — required-field validation', () => {
  it('rejects a blank user id with the verbatim message and issues no call', async () => {
    renderSignonPage();

    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });
    expect(signonMock).not.toHaveBeenCalled();
    expect(currentPath()).toBe(SIGNON_ROUTE);
  });

  it('marks the rejected entry field, not only the message line', async () => {
    // `COSGN00C` sends the map back with the cursor on the field it rejected
    // (`MOVE -1 TO USERIDL`) so the operator can see WHICH field the row-23 message is
    // about. It contains no `MOVE DFHRED`, so the field is NOT recoloured: the cursor
    // and the accessible invalid flag carry the mark, and nothing else does.
    renderSignonPage();

    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });
    expect(userIdField()).toBeInvalid();
    expect(userIdField()).toHaveFocus();
    expect(userIdField()).toHaveClass('field');
    expect(userIdField()).not.toHaveClass('fieldError');
    expect(passwordField()).toHaveClass('field');
    expect(passwordField()).not.toHaveClass('fieldError');
    expect(passwordField()).not.toBeInvalid();
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
  });

  it('moves the mark to the password when that is the field rejected', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_PASSWORD);
    });
    expect(passwordField()).toBeInvalid();
    expect(passwordField()).toHaveFocus();
    expect(passwordField()).not.toHaveClass('fieldError');
    expect(userIdField()).not.toHaveClass('fieldError');
    expect(userIdField()).not.toBeInvalid();
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
  });

  it('treats a whitespace-only user id as blank', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), '   ');
    await user.type(passwordField(), TEST_PASSWORD);
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });
    expect(signonMock).not.toHaveBeenCalled();
  });

  it('rejects a blank password with the verbatim message and issues no call', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_PASSWORD);
    });
    expect(signonMock).not.toHaveBeenCalled();
  });

  /*
   * Each of these is a ``MOVE -1 TO <field>L`` in COSGN00C, performed before the map is
   * re-sent: USERIDL for the blank user id (L121), the unknown user (L250) and the failed
   * verification (L255); PASSWDL for the blank password (L126) and the wrong password
   * (L244). The blank-password case is the one an operator meets first, and it is what
   * carries the cursor from the user id to the password field.
   */
  it('moves the cursor to the password field when only the password is blank', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_PASSWORD);
    });
    expect(passwordField()).toHaveFocus();
    expect(passwordField()).toHaveAttribute('aria-invalid', 'true');
    expect(userIdField()).not.toHaveFocus();
  });

  it('leaves the cursor on the user id when the user id is the rejected field', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(passwordField(), TEST_PASSWORD);
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });
    expect(userIdField()).toHaveFocus();
    expect(passwordField()).not.toHaveFocus();
  });

  it('reports the user id first when both entry fields are blank', async () => {
    renderSignonPage();

    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });
    expect(messageText()).not.toBe(MSG_ENTER_PASSWORD);
  });

  it('clears a previous message once both entry fields are supplied', async () => {
    signonMock.mockResolvedValue(standardSignonResponse);
    renderSignonPage();

    await pressEnterKey();
    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });

    await submitCredentials(STANDARD_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(MAIN_MENU_ROUTE);
    });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(messageText()).toBe('');
  });
});

describe('SignonPage — successful sign-on routing', () => {
  it('routes an administrator to the administrator menu', async () => {
    signonMock.mockResolvedValue(adminSignonResponse);
    const view = renderSignonPage();

    await submitCredentials(MIXED_CASE_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });
    expect(screen.getByTestId('admin-menu')).toBeInTheDocument();
    expect(screen.queryByTestId('main-menu')).toBeNull();
    expect(authenticatedFlag(view)).toBe('true');
  });

  it('routes a standard user to the main menu', async () => {
    signonMock.mockResolvedValue(standardSignonResponse);
    const view = renderSignonPage();

    await submitCredentials(STANDARD_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(MAIN_MENU_ROUTE);
    });
    expect(screen.getByTestId('main-menu')).toBeInTheDocument();
    expect(screen.queryByTestId('admin-menu')).toBeNull();
    expect(authenticatedFlag(view)).toBe('true');
  });

  it('routes on the verbatim SEC-USR-TYPE administrator code A', async () => {
    signonMock.mockResolvedValue({
      userId: ADMIN_USER_ID,
      userType: 'A',
      redirectTarget: 'CA00',
    });
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });
    expect(CDEMO_USRTYP_ADMIN).toBe('A');
  });

  it('routes on the verbatim SEC-USR-TYPE standard-user code U', async () => {
    signonMock.mockResolvedValue({
      userId: STANDARD_USER_ID,
      userType: 'U',
      redirectTarget: 'CM00',
    });
    renderSignonPage();

    await submitCredentials(STANDARD_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(MAIN_MENU_ROUTE);
    });
    expect(CDEMO_USRTYP_USER).toBe('U');
  });

  it('posts the upper-cased user id with the password unchanged, exactly once', async () => {
    signonMock.mockResolvedValue(adminSignonResponse);
    renderSignonPage();

    await submitCredentials(MIXED_CASE_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(signonMock).toHaveBeenCalledTimes(1);
    });
    expect(signonMock).toHaveBeenCalledWith({
      userId: ADMIN_USER_ID,
      password: TEST_PASSWORD,
    });
  });

  it('establishes the session for the signed-on user and role', async () => {
    signonMock.mockResolvedValue(adminSignonResponse);
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });
    // The session is server-authoritative: the identity and role are published by
    // ``POST /auth/signon`` and held only in memory, so the browser keeps no copy
    // that a later load could take authority from.
    expect(signonMock).toHaveBeenCalledWith({
      userId: ADMIN_USER_ID,
      password: TEST_PASSWORD,
    });
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe('SignonPage — rejected sign-on', () => {
  it('surfaces the verbatim wrong-password message on a 401', async () => {
    signonMock.mockRejectedValue(unauthorizedError(MSG_WRONG_PASSWORD));
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(messageText()).toBe(MSG_WRONG_PASSWORD);
    });
  });

  it('surfaces the verbatim user-not-found message on a 401', async () => {
    signonMock.mockRejectedValue(unauthorizedError(MSG_USER_NOT_FOUND));
    renderSignonPage();

    await submitCredentials(STANDARD_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(messageText()).toBe(MSG_USER_NOT_FOUND);
    });
  });

  it('falls back to the verify message when a 401 carries no body message', async () => {
    signonMock.mockRejectedValue(new ApiError(HTTP_UNAUTHORIZED, MSG_WRONG_PASSWORD));
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(messageText()).toBe(MSG_UNABLE_TO_VERIFY);
    });
  });

  it('falls back to the verify message for a failure that is not an ApiError', async () => {
    signonMock.mockRejectedValue(new Error('network unavailable'));
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(messageText()).toBe(MSG_UNABLE_TO_VERIFY);
    });
  });

  it('stays signed out on the sign-on route and keeps the entered user id', async () => {
    signonMock.mockRejectedValue(unauthorizedError(MSG_WRONG_PASSWORD));
    const view = renderSignonPage();

    await submitCredentials(MIXED_CASE_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(messageText()).toBe(MSG_WRONG_PASSWORD);
    });
    expect(currentPath()).toBe(SIGNON_ROUTE);
    expect(authenticatedFlag(view)).toBe('false');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(userIdField()).toHaveValue(ADMIN_USER_ID);
    expect(passwordField()).toHaveValue(TEST_PASSWORD);
  });
});

describe('SignonPage — ended session', () => {
  it('reports an expired session on the line-23 message region', async () => {
    // The operator is signed on and working when the server session goes away; the
    // centralized ``401`` path ends the session, the route guard returns them here,
    // and the screen has to say why rather than presenting a blank sign-on.
    await seedSignedOnSession(ADMIN_USER_ID, CDEMO_USRTYP_ADMIN);
    renderSignonPage();
    expect(expiryHandler).toBeDefined();

    act(() => {
      expiryHandler?.('expired');
    });

    await waitFor(() => {
      expect(messageText()).toBe(SESSION_ENDED_MESSAGE);
    });
    expect(screen.getByRole('alert')).toHaveTextContent(SESSION_ENDED_MESSAGE);
    expect(currentPath()).toBe(SIGNON_ROUTE);
  });

  it('says nothing for a refusal, which the refusing program reports itself', async () => {
    await seedSignedOnSession(ADMIN_USER_ID, CDEMO_USRTYP_ADMIN);
    renderSignonPage();

    act(() => {
      expiryHandler?.('refused');
    });

    expect(messageText()).toBe('');
  });

  it('withdraws the expiry message once the operator signs on again', async () => {
    await seedSignedOnSession(ADMIN_USER_ID, CDEMO_USRTYP_ADMIN);
    signonMock.mockResolvedValue(adminSignonResponse);
    renderSignonPage();
    act(() => {
      expiryHandler?.('expired');
    });
    await waitFor(() => {
      expect(messageText()).toBe(SESSION_ENDED_MESSAGE);
    });

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });
    expect(messageText()).toBe('');
  });

  it('lets a rejected retry replace the expiry message with its own', async () => {
    await seedSignedOnSession(ADMIN_USER_ID, CDEMO_USRTYP_ADMIN);
    signonMock.mockRejectedValue(unauthorizedError(MSG_WRONG_PASSWORD));
    renderSignonPage();
    act(() => {
      expiryHandler?.('expired');
    });
    await waitFor(() => {
      expect(messageText()).toBe(SESSION_ENDED_MESSAGE);
    });

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);

    await waitFor(() => {
      expect(messageText()).toBe(MSG_WRONG_PASSWORD);
    });
  });
});

describe('SignonPage — PF-key wiring', () => {
  it('submits on the ENTER AID handled by the line-24 legend', async () => {
    signonMock.mockResolvedValue(adminSignonResponse);
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await user.type(passwordField(), TEST_PASSWORD);
    await pressEnterKey();

    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });
    expect(signonMock).toHaveBeenCalledTimes(1);
  });

  it('submits on implicit form submission from an entry field', async () => {
    signonMock.mockResolvedValue(standardSignonResponse);
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), STANDARD_USER_ID);
    await user.type(passwordField(), TEST_PASSWORD);
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(currentPath()).toBe(MAIN_MENU_ROUTE);
    });
    expect(signonMock).toHaveBeenCalledTimes(1);
  });

  it('submits when the ENTER=Sign-on legend key is activated', async () => {
    signonMock.mockResolvedValue(adminSignonResponse);
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await user.type(passwordField(), TEST_PASSWORD);
    await user.click(screen.getByRole('button', { name: PF_ENTER_LABEL }));

    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });
    expect(signonMock).toHaveBeenCalledTimes(1);
  });

  it('clears both entry fields and the message on the PF3 AID', async () => {
    renderSignonPage();

    await pressEnterKey();
    await waitFor(() => {
      expect(messageText()).toBe(MSG_ENTER_USER_ID);
    });

    const user = userEvent.setup();
    await user.type(userIdField(), ADMIN_USER_ID);
    await user.type(passwordField(), TEST_PASSWORD);
    await pressPf3Key();

    // ``COSGN00C`` L88-89 sends CCDA-MSG-THANK-YOU with ERASE and returns without a
    // transaction id, so the erased screen carries that one line and no entry field.
    await waitFor(() => {
      expect(screen.getByText(CCDA_MSG_THANK_YOU)).toBeInTheDocument();
    });
    expect(screen.queryByLabelText('User ID :')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Password :')).not.toBeInTheDocument();
    expect(currentPath()).toBe(SIGNON_ROUTE);
    expect(signonMock).not.toHaveBeenCalled();
  });

  it('clears the screen when the F3=Exit legend key is activated', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await user.type(passwordField(), TEST_PASSWORD);
    await user.click(screen.getByRole('button', { name: PF_EXIT_LABEL }));

    await waitFor(() => {
      expect(screen.getByText(CCDA_MSG_THANK_YOU)).toBeInTheDocument();
    });
    expect(screen.queryByLabelText('User ID :')).not.toBeInTheDocument();
    expect(currentPath()).toBe(SIGNON_ROUTE);
    expect(signonMock).not.toHaveBeenCalled();
  });

  it('signs an established session out on exit', async () => {
    await seedSignedOnSession(ADMIN_USER_ID, CDEMO_USRTYP_ADMIN);
    const view = renderSignonPage();
    expect(authenticatedFlag(view)).toBe('true');

    await pressPf3Key();

    await waitFor(() => {
      expect(authenticatedFlag(view)).toBe('false');
    });
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('ignores keys outside the declared legend', async () => {
    renderSignonPage();
    const user = userEvent.setup();

    await user.type(userIdField(), ADMIN_USER_ID);
    await user.type(passwordField(), TEST_PASSWORD);
    fireEvent.keyDown(document, { key: 'F8' });

    await waitFor(() => {
      expect(userIdField()).toHaveValue(ADMIN_USER_ID);
    });
    expect(passwordField()).toHaveValue(TEST_PASSWORD);
    expect(signonMock).not.toHaveBeenCalled();
    expect(currentPath()).toBe(SIGNON_ROUTE);
  });
});

describe('SignonPage — password confidentiality', () => {
  it('never renders the entered password as readable text', async () => {
    const view = renderSignonPage();
    const user = userEvent.setup();

    await user.type(passwordField(), TEST_PASSWORD);

    expect(passwordField()).toHaveValue(TEST_PASSWORD);
    expect(passwordField()).toHaveAttribute('type', 'password');
    expect(view.container.textContent ?? '').not.toContain(TEST_PASSWORD);
    expect(screen.queryByText(TEST_PASSWORD)).toBeNull();
  });

  it('never writes the password to the console', async () => {
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const infoSpy = jest.spyOn(console, 'info').mockImplementation(() => undefined);
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const debugSpy = jest.spyOn(console, 'debug').mockImplementation(() => undefined);
    signonMock.mockRejectedValue(unauthorizedError(MSG_WRONG_PASSWORD));
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);
    await waitFor(() => {
      expect(messageText()).toBe(MSG_WRONG_PASSWORD);
    });

    const logged = [
      renderCalls(logSpy.mock.calls),
      renderCalls(infoSpy.mock.calls),
      renderCalls(warnSpy.mock.calls),
      renderCalls(errorSpy.mock.calls),
      renderCalls(debugSpy.mock.calls),
    ].join('\n');
    expect(logged).not.toContain(TEST_PASSWORD);
  });

  it('never persists the password to session storage', async () => {
    signonMock.mockResolvedValue(adminSignonResponse);
    renderSignonPage();

    await submitCredentials(ADMIN_USER_ID, TEST_PASSWORD);
    await waitFor(() => {
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    });

    // Nothing at all is written to browser storage, so the password cannot be in it.
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(sessionStorage.length).toBe(0);
    const stored = JSON.stringify(sessionStorage);
    expect(stored).not.toContain(TEST_PASSWORD);
    expect(stored.toLowerCase()).not.toContain('password');
  });
});
