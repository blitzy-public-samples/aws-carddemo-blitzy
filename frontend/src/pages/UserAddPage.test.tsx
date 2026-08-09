/**
 * UserAddPage tests
 * =================
 *
 * :purpose: Verify the administrator add-user screen — the React replacement for
 *     BMS mapset ``app/bms/COUSR01.bms`` (CICS transaction ``CU01``, program
 *     ``app/cbl/COUSR01C.cbl``): the five ``COUSR1AI`` entry fields at their
 *     symbolic-map widths (``app/cpy-bms/COUSR01.CPY``), the masked password
 *     field, the verbatim blank-field edits in their legacy evaluation order,
 *     the ``POST /users`` payload and confirmation message, the duplicate
 *     user-id rejection, and the line-24 ENTER / F3 / F4 wiring.
 * :output: Jest assertions only; the suite writes no files and issues no
 *     network calls (``../api`` is mocked).
 */
import { jest } from '@jest/globals';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { ReactElement } from 'react';
import type {
  ApiErrorResponse,
  Role,
  SignonRequestDto,
  SignonResponseDto,
  UserAddRequestDto,
  UserAddResponseDto,
} from '../types';

/** Stable mock of the ``../api`` ``addUser`` export (``POST /users``). */
const addUserMock =
  jest.fn<(request: UserAddRequestDto) => Promise<UserAddResponseDto>>();

/**
 * Stable mock of the ``../api`` ``signon`` export: the ``../hooks`` barrel pulls
 * in ``useSession``, which imports ``signon``, so the mocked module must provide
 * that name for the ES-module link to resolve.
 */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

/**
 * ``ApiError`` stand-in exported by the mocked ``../api``, carrying the same
 * members as ``api/client.ts`` (``status``, ``body``,
 * ``isOptimisticLockConflict``). Rejected calls are constructed from this class,
 * which ``useApi`` narrows with ``instanceof``; the real client — which
 * transitively reads ``import.meta`` — is never loaded.
 */
class ApiError extends Error {
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

jest.unstable_mockModule('../api', () => ({
  // The request-cancellation contract ``useApi`` binds to: the real scope hands the
  // caller's AbortSignal to axios, and the double simply invokes the call.
  runWithRequestSignal: (_signal: AbortSignal, call: () => unknown): unknown => call(),
  isCancelledRequest: (): boolean => false,
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. ``getSessionIdentity`` is the production
  // ``GET /session`` probe the session harness drives; unanswered by this suite it
  // reports no session, and the harness is the only thing that changes that.
  getSessionIdentity: jest.fn(() => Promise.reject(new Error('No session'))),
  logout: jest.fn(() => Promise.resolve(undefined)),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  addUser: addUserMock,
  signon: signonMock,
  ApiError,
}));

/** Route of the add-user screen (``AdminMenuPage`` option 2 -> ``COUSR01C``). */
const ADD_USER_ROUTE = '/users/add';

/** Route of the administrator menu reached by PF3 (``XCTL 'COADM01C'``). */
const ADMIN_MENU_ROUTE = '/admin';

/** Administrator id seeded into the session store before each test. */
const ADMIN_USER_ID = 'ADMIN001';

/** Administrator role code — COMMAREA ``88 CDEMO-USRTYP-ADMIN VALUE 'A'``. */
const ADMIN_ROLE: Role = 'A';

/** BMS prompt of the ``FNAMEI`` field. */
const LABEL_FIRST_NAME = 'First Name:';

/** BMS prompt of the ``LNAMEI`` field. */
const LABEL_LAST_NAME = 'Last Name:';

/** BMS prompt of the ``USERIDI`` field. */
const LABEL_USER_ID = 'User ID:';

/** BMS prompt of the ``PASSWDI`` field. */
const LABEL_PASSWORD = 'Password:';

/** BMS prompt of the ``USRTYPEI`` field. */
const LABEL_USER_TYPE = 'User Type:';

/** Entry-field capacities declared by the ``COUSR1AI`` symbolic map. */
const FIELD_WIDTHS: ReadonlyArray<readonly [string, string]> = [
  [LABEL_FIRST_NAME, '20'],
  [LABEL_LAST_NAME, '20'],
  [LABEL_USER_ID, '8'],
  [LABEL_PASSWORD, '8'],
  [LABEL_USER_TYPE, '1'],
];

/** Verbatim ``COUSR01C`` blank-field message for ``FNAMEI``. */
const MSG_FIRST_NAME_EMPTY = 'First Name can NOT be empty...';

/** Verbatim ``COUSR01C`` blank-field message for ``LNAMEI``. */
const MSG_LAST_NAME_EMPTY = 'Last Name can NOT be empty...';

/** Verbatim ``COUSR01C`` blank-field message for ``USERIDI``. */
const MSG_USER_ID_EMPTY = 'User ID can NOT be empty...';

/** Verbatim ``COUSR01C`` blank-field message for ``PASSWDI``. */
const MSG_PASSWORD_EMPTY = 'Password can NOT be empty...';

/** Verbatim ``COUSR01C`` blank-field message for ``USRTYPEI``. */
const MSG_USER_TYPE_EMPTY = 'User Type can NOT be empty...';

/** Verbatim ``COUSR01C`` duplicate-key message returned by the backend. */
const MSG_DUPLICATE_USER_ID = 'User ID already exist...';

/** Verbatim ``(8 Char)`` hint shown after ``USERIDI`` and ``PASSWDI``. */
const HINT_EIGHT_CHAR = '(8 Char)';

/** Verbatim ``USRTYPEI`` hint. */
const HINT_USER_TYPE = '(A=Admin, U=User)';

/** Line-24 legend text of the ENTER key. */
const PF_ENTER_LABEL = 'ENTER=Add User';

/** Line-24 legend text of the PF3 key. */
const PF_EXIT_LABEL = 'F3=Back';

/** Line-24 legend text of the PF4 key. */
const PF_CLEAR_LABEL = 'F4=Clear';

/**
 * Entered field values, mixed case: the request assertions confirm they reach
 * ``POST /users`` verbatim, with no upper-casing.
 */
const NEW_USER: UserAddRequestDto = {
  userId: 'NEWUSER1',
  firstName: 'John',
  lastName: 'Doe',
  userType: 'U',
  password: 'FakePw01',
};

/** Password-free user returned by a successful ``POST /users``. */
const CREATED_USER: UserAddResponseDto = {
  userId: NEW_USER.userId,
  firstName: NEW_USER.firstName,
  lastName: NEW_USER.lastName,
  userType: NEW_USER.userType,
  // The real route returns the legacy banner here; null exercises the client-side
  // fallback that composes it from the SERVER-normalized userId.
  message: null,
};

/** Confirmation message built from the entered id (``'User ' + id + ...``). */
const MSG_USER_ADDED = `User ${NEW_USER.userId} has been added ...`;

/**
 * Standardized backend error body for a duplicate user id: ``UserService``
 * raises ``CardDemoException('User ID already exist...')``, which the shared
 * exception handler maps to HTTP 400.
 */
const DUPLICATE_ERROR_BODY: ApiErrorResponse = {
  timestamp: '2026-08-05T12:00:00.000Z',
  status: 400,
  error: 'Bad Request',
  message: MSG_DUPLICATE_USER_ID,
  path: '/users',
};

type LayoutComponent = (typeof import('../components/Layout'))['default'];
type UserAddPageComponent = (typeof import('./UserAddPage'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let Layout: LayoutComponent;
let UserAddPage: UserAddPageComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the screen, the hooks, and the
  // shared shell all bind to the mocked ``../api``.
  ({ default: Layout } = await import('../components/Layout'));
  ({ default: UserAddPage } = await import('./UserAddPage'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  addUserMock.mockReset();
  signonMock.mockReset();
  await seedSignedOnSession(ADMIN_USER_ID, ADMIN_ROLE);
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * :purpose: Surface the current router pathname so the PF3 exit can be asserted.
 * :returns: A span holding the active pathname.
 */
function LocationProbe(): ReactElement {
  const { pathname } = useLocation();
  return <span data-testid="location">{pathname}</span>;
}

/**
 * :purpose: Render the add-user screen inside the shared 24x80 shell at its own
 *     route, so the published chrome (message region and function-key bar) is
 *     observable.
 */
function renderAddUserScreen(): void {
  render(
    <MemoryRouter initialEntries={[ADD_USER_ROUTE]}>
      <Layout>
        <UserAddPage />
      </Layout>
      <LocationProbe />
    </MemoryRouter>,
  );
}

/**
 * :purpose: Set a controlled entry field to a value.
 * :param label: BMS prompt labelling the field.
 * :param value: Value to enter.
 */
function typeField(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

/** :purpose: Fill all five entry fields with :data:`NEW_USER`. */
function fillEveryField(): void {
  typeField(LABEL_FIRST_NAME, NEW_USER.firstName);
  typeField(LABEL_LAST_NAME, NEW_USER.lastName);
  typeField(LABEL_USER_ID, NEW_USER.userId);
  typeField(LABEL_PASSWORD, NEW_USER.password);
  typeField(LABEL_USER_TYPE, NEW_USER.userType);
}

/**
 * :purpose: Press a physical 3270 AID key on the document, the path the shared
 *     function-key bar listens on.
 * :param key: DOM key name (``Enter``, ``F3``, ``F4``).
 */
async function pressAidKey(key: string): Promise<void> {
  await act(async () => {
    fireEvent.keyDown(document, { key });
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Activate a function key through its line-24 legend button.
 * :param label: Verbatim legend text of the button.
 */
async function clickPfKey(label: string): Promise<void> {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: label }));
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Read the line-23 message region.
 * :param role: ``alert`` for the error message, ``status`` for the confirmation.
 * :returns: The rendered message text.
 * :note: The shared shell also renders a visually hidden ``role="status"`` busy
 *     announcer, so the informational banner is matched on its own class.
 */
function messageText(role: 'alert' | 'status'): string {
  const region =
    role === 'alert' ? screen.getByRole('alert') : infoBanner();
  return region?.textContent ?? '';
}

/** :purpose: Assert every entry field is empty (``INITIALIZE-ALL-FIELDS``). */
function expectEveryFieldEmpty(): void {
  for (const [label] of FIELD_WIDTHS) {
    expect(screen.getByLabelText(label)).toHaveValue('');
  }
}

/**
 * :purpose: The line-23 informational message region. The shared shell also renders a
 *     visually hidden ``role="status"`` busy announcer, so the banner is matched on its
 *     own class rather than on the role alone.
 * :returns: the informational banner, or ``null`` when line 23 carries no
 *     informational message.
 */
function infoBanner(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.errorBanner[role="status"]');
}

describe('UserAddPage — COUSR01 entry fields', () => {
  it('renders the five entry fields at their COUSR1AI widths', () => {
    renderAddUserScreen();

    for (const [label, width] of FIELD_WIDTHS) {
      const field = screen.getByLabelText(label);
      expect(field).toBeInTheDocument();
      expect(field).toHaveAttribute('maxlength', width);
    }
    expectEveryFieldEmpty();
  });

  it('masks the password field and leaves the other four unmasked', () => {
    renderAddUserScreen();

    const password = screen.getByLabelText(LABEL_PASSWORD);
    expect(password).toHaveAttribute('type', 'password');

    for (const label of [
      LABEL_FIRST_NAME,
      LABEL_LAST_NAME,
      LABEL_USER_ID,
      LABEL_USER_TYPE,
    ]) {
      expect(screen.getByLabelText(label)).toHaveAttribute('type', 'text');
    }

    typeField(LABEL_PASSWORD, NEW_USER.password);
    expect(password).toHaveValue(NEW_USER.password);
    expect(password).toHaveAttribute('type', 'password');
  });

  it('renders the BMS field hints verbatim', () => {
    renderAddUserScreen();

    expect(screen.getAllByText(HINT_EIGHT_CHAR)).toHaveLength(2);
    expect(screen.getByText(HINT_USER_TYPE)).toBeInTheDocument();
  });

  it('publishes the CU01 / COUSR01C chrome for the authenticated administrator', () => {
    const { container } = render(
      <MemoryRouter initialEntries={[ADD_USER_ROUTE]}>
        <Layout>
          <UserAddPage />
        </Layout>
      </MemoryRouter>,
    );

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CU01');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COUSR01C');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    expect(
      screen.getByRole('heading', { name: 'Add User', level: 3 }),
    ).toBeInTheDocument();
    expect(container.querySelector('.screen')).toHaveAttribute(
      'data-authenticated',
      'true',
    );
  });
});

describe('UserAddPage — blank-field edits (COUSR01C PROCESS-ENTER-KEY)', () => {
  it('reports the first blank field in the legacy evaluation order', async () => {
    renderAddUserScreen();

    await pressAidKey('Enter');
    expect(messageText('alert')).toBe(MSG_FIRST_NAME_EMPTY);

    typeField(LABEL_FIRST_NAME, NEW_USER.firstName);
    await pressAidKey('Enter');
    expect(messageText('alert')).toBe(MSG_LAST_NAME_EMPTY);

    typeField(LABEL_LAST_NAME, NEW_USER.lastName);
    await pressAidKey('Enter');
    expect(messageText('alert')).toBe(MSG_USER_ID_EMPTY);

    typeField(LABEL_USER_ID, NEW_USER.userId);
    await pressAidKey('Enter');
    expect(messageText('alert')).toBe(MSG_PASSWORD_EMPTY);

    typeField(LABEL_PASSWORD, NEW_USER.password);
    await pressAidKey('Enter');
    expect(messageText('alert')).toBe(MSG_USER_TYPE_EMPTY);

    expect(addUserMock).not.toHaveBeenCalled();
  });

  it('treats a blank-filled field as empty and keeps the entered values', async () => {
    renderAddUserScreen();

    fillEveryField();
    typeField(LABEL_USER_TYPE, ' ');
    await pressAidKey('Enter');

    expect(messageText('alert')).toBe(MSG_USER_TYPE_EMPTY);
    expect(addUserMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText(LABEL_USER_ID)).toHaveValue(NEW_USER.userId);
  });
});

describe('UserAddPage — successful add (POST /users)', () => {
  it('sends the entered fields verbatim and confirms the new user id', async () => {
    addUserMock.mockResolvedValue(CREATED_USER);
    renderAddUserScreen();

    fillEveryField();
    await pressAidKey('Enter');

    expect(addUserMock).toHaveBeenCalledTimes(1);
    expect(addUserMock).toHaveBeenCalledWith(NEW_USER);

    const [request] = addUserMock.mock.calls[0];
    expect(request.password).toBe(NEW_USER.password);
    expect(request.userType).toBe(NEW_USER.userType);

    expect(messageText('status')).toBe(MSG_USER_ADDED);
    expect(screen.queryByRole('alert')).toBeNull();
    expectEveryFieldEmpty();
  });

  it('adds the user when the ENTER legend button is activated', async () => {
    addUserMock.mockResolvedValue(CREATED_USER);
    renderAddUserScreen();

    fillEveryField();
    await clickPfKey(PF_ENTER_LABEL);

    expect(addUserMock).toHaveBeenCalledTimes(1);
    expect(addUserMock).toHaveBeenCalledWith(NEW_USER);
    expect(messageText('status')).toBe(MSG_USER_ADDED);
  });

  it('names the id the SERVICE stored, not the lower-case text that was typed', async () => {
    // The service upper-cases SEC-USR-ID (COSGN00C L132 / the 3270 UCTRAN attribute),
    // so a lower-case entry is stored upper-cased. Building the banner from the entered
    // text would name an id that is NOT the one stored and make a correct normalization
    // look broken to the operator.
    const typedLowerCase = 'newuser1';
    addUserMock.mockResolvedValue({ ...CREATED_USER, userId: 'NEWUSER1' });
    renderAddUserScreen();

    typeField(LABEL_FIRST_NAME, NEW_USER.firstName);
    typeField(LABEL_LAST_NAME, NEW_USER.lastName);
    typeField(LABEL_USER_ID, typedLowerCase);
    typeField(LABEL_PASSWORD, NEW_USER.password);
    typeField(LABEL_USER_TYPE, NEW_USER.userType);
    await clickPfKey(PF_ENTER_LABEL);

    expect(messageText('status')).toBe('User NEWUSER1 has been added ...');
    expect(messageText('status')).not.toContain(typedLowerCase);
  });

  it('renders the verbatim banner the service returned when it supplies one', async () => {
    // UserWriteResponseDto carries the legacy confirmation text; when present it is
    // rendered as-is rather than recomposed on the client.
    addUserMock.mockResolvedValue({
      ...CREATED_USER,
      message: 'User NEWUSER1 has been added ...',
    });
    renderAddUserScreen();

    fillEveryField();
    await clickPfKey(PF_ENTER_LABEL);

    expect(messageText('status')).toBe('User NEWUSER1 has been added ...');
  });
});

describe('UserAddPage — in-flight duplicate-add guard', () => {
  it('closes every entry field while the add is in flight and posts once for repeated ENTER', async () => {
    let acknowledge!: (value: UserAddResponseDto) => void;
    addUserMock.mockReturnValueOnce(
      new Promise<UserAddResponseDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    renderAddUserScreen();

    fillEveryField();
    await pressAidKey('Enter');

    // ``ATTRB=ASKIP`` for the whole in-flight interval: no field, not even the
    // password, can be retyped while the POST is outstanding.
    for (const [label] of FIELD_WIDTHS) {
      expect(screen.getByLabelText(label)).toBeDisabled();
    }

    // A second ENTER, from the physical key and from the legend, must not add twice.
    await pressAidKey('Enter');
    await clickPfKey(PF_ENTER_LABEL);
    expect(addUserMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge(CREATED_USER);
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(addUserMock).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText(LABEL_USER_ID)).toBeEnabled();
  });

  it('posts once for three activations dispatched in the same task', async () => {
    addUserMock.mockResolvedValue(CREATED_USER);
    renderAddUserScreen();
    fillEveryField();

    // Nothing is awaited between the three activations, so React state has not
    // advanced for any of them: only a synchronous latch can hold the keyboard.
    const enterKey = screen.getByRole('button', { name: PF_ENTER_LABEL });
    await act(async () => {
      fireEvent.click(enterKey);
      fireEvent.click(enterKey);
      fireEvent.click(enterKey);
      await Promise.resolve();
    });

    expect(addUserMock).toHaveBeenCalledTimes(1);
    expect(addUserMock).toHaveBeenCalledWith(NEW_USER);
  });
});

describe('UserAddPage — duplicate user id', () => {
  it('surfaces the backend duplicate message and keeps the entered fields', async () => {
    addUserMock.mockRejectedValue(
      new ApiError(400, MSG_DUPLICATE_USER_ID, DUPLICATE_ERROR_BODY),
    );
    renderAddUserScreen();

    fillEveryField();
    await pressAidKey('Enter');

    expect(addUserMock).toHaveBeenCalledTimes(1);
    expect(messageText('alert')).toBe(MSG_DUPLICATE_USER_ID);
    expect(infoBanner()).toBeNull();
    expect(screen.getByLabelText(LABEL_USER_ID)).toHaveValue(NEW_USER.userId);
    expect(screen.getByLabelText(LABEL_FIRST_NAME)).toHaveValue(
      NEW_USER.firstName,
    );
  });
});

describe('UserAddPage — line-24 function keys', () => {
  it('renders the ENTER / F3 / F4 legend', () => {
    renderAddUserScreen();

    expect(
      screen.getByRole('group', { name: 'Function keys' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: PF_ENTER_LABEL }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: PF_EXIT_LABEL }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: PF_CLEAR_LABEL }),
    ).toBeInTheDocument();
  });

  it('exits to the administrator menu on F3', async () => {
    renderAddUserScreen();
    expect(screen.getByTestId('location')).toHaveTextContent(ADD_USER_ROUTE);

    await pressAidKey('F3');

    expect(screen.getByTestId('location')).toHaveTextContent(ADMIN_MENU_ROUTE);
    expect(addUserMock).not.toHaveBeenCalled();
  });

  it('clears every entry field and the message on F4', async () => {
    renderAddUserScreen();

    typeField(LABEL_FIRST_NAME, NEW_USER.firstName);
    await pressAidKey('Enter');
    expect(messageText('alert')).toBe(MSG_LAST_NAME_EMPTY);

    await pressAidKey('F4');

    expectEveryFieldEmpty();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(addUserMock).not.toHaveBeenCalled();
  });
});
