/**
 * UserUpdatePage tests
 * ====================
 *
 * :purpose: Verify the administrator update-user workflow of ``UserUpdatePage``
 *     (mapset ``app/bms/COUSR02.bms``, CICS transaction ``CU02``, program
 *     ``app/cbl/COUSR02C.cbl``): the fetch-by-id lookup that seeds the editable
 *     fields, the masked password field, the not-found outcome, the five
 *     required-field messages, the no-change guard, the save outcome, and the
 *     line-24 function keys ``ENTER`` / ``F3`` / ``F4`` / ``F5``.
 * :output: Jest assertions only; nothing is written and no request is issued.
 * :note: The page publishes its line-23 message region and its line-24 key bar
 *     into the shared shell through ``useScreenChrome``, so every case renders it
 *     inside :func:`Layout` and asserts against the shell.
 * :note: ``../api`` is replaced through ``jest.unstable_mockModule``, so neither
 *     axios nor ``import.meta`` is ever evaluated: ``getUser`` and ``updateUser``
 *     are Jest mocks, ``signon`` is stubbed for the session store, and
 *     ``ApiError`` is passed through as a class carrying the client's public shape
 *     (``status`` / ``body`` / ``isOptimisticLockConflict``) so the ``useApi``
 *     ``instanceof`` narrowing holds. The page, the shell, and the session seam
 *     are imported dynamically after the mock is registered, without a registry
 *     reset, so React and Testing Library stay a single instance.
 */

import { jest } from '@jest/globals';
import {
  act,
  createEvent,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE02, CCDA_MSG_INVALID_KEY } from '../types';
import type {
  ApiErrorResponse,
  SignonRequestDto,
  SignonResponseDto,
  UserDto,
  UserUpdateRequestDto,
} from '../types';

/**
 * :purpose: Passthrough ``ApiError`` published by the mocked ``../api`` barrel: a
 *     real ``Error`` subclass carrying the normalized client contract, so a
 *     rejected call narrows through ``instanceof`` inside ``useApi`` and the page
 *     reads ``status`` and ``body`` exactly as it does against the real client.
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: already-resolved, human-readable error message.
 * :param body: standardized backend error body when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the ``409`` conflict.
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

/** Keyed read of one security user (``GET /users/{id}``). */
const getUserMock = jest.fn<(userId: string) => Promise<UserDto>>();

/** Rewrite of one security user (``PUT /users/{id}``). */
const updateUserMock =
  jest.fn<
    (userId: string, request: UserUpdateRequestDto) => Promise<UserDto>
  >();

/** Sign-on stub required by the session store's ``../api`` import. */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

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
  ApiError,
  getUser: getUserMock,
  updateUser: updateUserMock,
  signon: signonMock,
}));

/** Client route of the update-user screen. */
const SCREEN_ROUTE = '/users/update';

/** Client route of the administrator menu, the ``COADM01C`` return target. */
const ADMIN_MENU_ROUTE = '/admin';

/** Marker rendered by the administrator-menu route. */
const ADMIN_MENU_TEST_ID = 'admin-menu';

/** Signed-in administrator seeded into the session store. */
const ADMIN_USER_ID = 'ADMIN001';

/** User id maintained by the screen under test. */
const TARGET_USER_ID = 'USER0001';

/** User id for which the keyed read misses. */
const MISSING_USER_ID = 'NOSUCH01';

/** CICS transaction id and program name the screen publishes to the header. */
const TRANSACTION_ID = 'CU02';
const PROGRAM_NAME = 'COUSR02C';

/** Screen heading of the mapset (line 4) and second header title line. */
const SCREEN_TITLE = 'Update User';

/** The stored record returned by the keyed read; ``UserDto`` is password-free. */
const STORED_USER: UserDto = {
  userId: TARGET_USER_ID,
  firstName: 'John',
  lastName: 'Doe',
  userType: 'U',
};

/** Replacement first name typed into ``FNAME`` before a save. */
const EDITED_FIRST_NAME = 'Jane';

/** Obviously fake eight-character password typed into ``PASSWD``. */
const ENTERED_PASSWORD = 'PASS9999';

/** Fixed instant stamped on the synthesized backend error bodies. */
const ERROR_TIMESTAMP = '2026-08-05T12:00:00.000Z';

const LABEL_USER_ID = 'Enter User ID:';
const LABEL_FIRST_NAME = 'First Name:';
const LABEL_LAST_NAME = 'Last Name:';
const LABEL_PASSWORD = 'Password:';
const LABEL_USER_TYPE = 'User Type:';

const HINT_PASSWORD = '(8 Char)';
const HINT_USER_TYPE = '(A=Admin, U=User)';

const MSG_USER_ID_EMPTY = 'User ID can NOT be empty...';
const MSG_FIRST_NAME_EMPTY = 'First Name can NOT be empty...';
const MSG_LAST_NAME_EMPTY = 'Last Name can NOT be empty...';
/** The refusal ``COUSR02C`` L198-L202 raises for a blank ``PASSWD``; the optional-password
 *  contract must never produce it. */
const MSG_PASSWORD_EMPTY = 'Password can NOT be empty...';
const MSG_USER_TYPE_EMPTY = 'User Type can NOT be empty...';
const MSG_USER_ID_NOT_FOUND = 'User ID NOT found...';
const MSG_PLEASE_MODIFY = 'Please modify to update ...';
const MSG_PRESS_PF5_UPDATE = 'Press PF5 key to save your updates ...';
const MSG_USER_UPDATED = `User ${TARGET_USER_ID} has been updated ...`;

const PF_ENTER_LABEL = 'ENTER=Fetch';
const PF3_LABEL = 'F3=Save&Exit';
const PF4_LABEL = 'F4=Clear';
const PF5_LABEL = 'F5=Save';

/** Line-24 legend of the PF12 action (``COUSR02`` ``FKEYSC``). */
const PF12_LABEL = 'F12=Cancel';

type UserUpdatePageComponent = (typeof import('./UserUpdatePage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let UserUpdatePage: UserUpdatePageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so every consumer of ``../api`` binds
  // to the mocked barrel; no registry reset, so React stays a single instance.
  ({ default: UserUpdatePage } = await import('./UserUpdatePage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getUserMock.mockReset();
  updateUserMock.mockReset();
  signonMock.mockReset();
  getUserMock.mockResolvedValue({ ...STORED_USER });
  updateUserMock.mockResolvedValue({ ...STORED_USER });
  await seedSignedOnSession(ADMIN_USER_ID, 'A');
});

afterEach(async () => {
  await seedSignedOutSession();
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.clear();
  }
});

/**
 * :purpose: Build a standardized backend error body for a rejected call.
 * :param status: HTTP status code carried by the body.
 * :param error: HTTP reason phrase.
 * :param message: the message the screen is expected to surface.
 * :returns: The synthesized :ts:type:`ApiErrorResponse`.
 */
function apiErrorBody(
  status: number,
  error: string,
  message: string,
): ApiErrorResponse {
  return {
    timestamp: ERROR_TIMESTAMP,
    status,
    error,
    message,
    path: `/users/${TARGET_USER_ID}`,
  };
}

/**
 * :purpose: Render the screen inside the shared shell, on the update-user route,
 *     with the administrator-menu route mounted so the ``F3=Save&Exit`` transfer
 *     is observable.
 * :param options: ``handoverUserId`` places a user id in the router state exactly
 *     as the user-list screen does; ``search`` supplies a query string instead.
 */
function renderScreen(
  options: { handoverUserId?: string; search?: string } = {},
): void {
  const { handoverUserId, search } = options;
  render(
    <MemoryRouter
      initialEntries={[
        {
          pathname: SCREEN_ROUTE,
          search: search ?? '',
          state:
            handoverUserId === undefined ? undefined : { userId: handoverUserId },
        },
      ]}
    >
      <Layout>
        <Routes>
          <Route path={SCREEN_ROUTE} element={<UserUpdatePage />} />
          <Route
            path={ADMIN_MENU_ROUTE}
            element={<div data-testid={ADMIN_MENU_TEST_ID}>Admin Menu</div>}
          />
        </Routes>
      </Layout>
    </MemoryRouter>,
  );
}

/**
 * :purpose: Locate an editable field of the screen by its mapset caption.
 * :param label: the caption reproduced from ``COUSR02.bms``.
 * :returns: The labelled input element.
 */
function field(label: string): HTMLElement {
  return screen.getByLabelText(label);
}

/**
 * :purpose: Locate a line-24 function key by its legend text.
 * :param label: the legend reproduced from ``COUSR02.bms``.
 * :returns: The function-key button.
 */
function pfKey(label: string): HTMLElement {
  return screen.getByRole('button', { name: label });
}

/**
 * :purpose: Render the screen with the user id handed over by the user-list
 *     screen and wait until the fetched record has seeded the editable fields.
 * :returns: A promise that settles once the record is on the screen.
 */
async function renderFetchedScreen(): Promise<void> {
  renderScreen({ handoverUserId: TARGET_USER_ID });
  // The prompt is published last, so awaiting it settles the whole screen.
  await waitFor(() => {
    expect(infoBanner()).toHaveTextContent(MSG_PRESS_PF5_UPDATE);
  });
  expect(field(LABEL_FIRST_NAME)).toHaveValue(STORED_USER.firstName);
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

describe('UserUpdatePage — fetch then edit', () => {
  it('looks the handed-over user id up and seeds the editable fields', async () => {
    renderScreen({ handoverUserId: TARGET_USER_ID });

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(MSG_PRESS_PF5_UPDATE);
    });
    expect(getUserMock).toHaveBeenCalledWith(TARGET_USER_ID);
    expect(getUserMock).toHaveBeenCalledTimes(1);
    expect(field(LABEL_USER_ID)).toHaveValue(TARGET_USER_ID);
    expect(field(LABEL_FIRST_NAME)).toHaveValue(STORED_USER.firstName);
    expect(field(LABEL_LAST_NAME)).toHaveValue(STORED_USER.lastName);
    expect(field(LABEL_USER_TYPE)).toHaveValue(STORED_USER.userType);
    // The response DTO is password-free, so the masked field is re-entered.
    expect(field(LABEL_PASSWORD)).toHaveValue('');
    expect(screen.getByTestId('tran-id')).toHaveTextContent(TRANSACTION_ID);
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(PROGRAM_NAME);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    // The screen name lives in the body heading (BMS row 4), not in title02.
    expect(
      screen.getByRole('heading', { level: 3, name: SCREEN_TITLE }),
    ).toBeInTheDocument();
  });

  it('renders the password field masked and both mapset hints', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    const password = field(LABEL_PASSWORD);
    expect(password).toHaveAttribute('type', 'password');

    await user.type(password, ENTERED_PASSWORD);

    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue(ENTERED_PASSWORD);
    expect(screen.getByText(HINT_PASSWORD)).toBeInTheDocument();
    expect(screen.getByText(HINT_USER_TYPE)).toBeInTheDocument();
  });

  it('fetches the typed user id when the ENTER legend key is activated', async () => {
    const user = userEvent.setup();
    renderScreen();

    expect(getUserMock).not.toHaveBeenCalled();

    await user.type(field(LABEL_USER_ID), TARGET_USER_ID);
    await user.click(pfKey(PF_ENTER_LABEL));

    await waitFor(() => {
      expect(field(LABEL_FIRST_NAME)).toHaveValue(STORED_USER.firstName);
    });
    expect(getUserMock).toHaveBeenCalledWith(TARGET_USER_ID);
    expect(field(LABEL_USER_TYPE)).toHaveValue(STORED_USER.userType);
  });

  it('fetches the user id handed over in the query string', async () => {
    renderScreen({ search: `?userId=${TARGET_USER_ID}` });

    await waitFor(() => {
      expect(field(LABEL_LAST_NAME)).toHaveValue(STORED_USER.lastName);
    });
    expect(getUserMock).toHaveBeenCalledWith(TARGET_USER_ID);
  });
});

describe('UserUpdatePage — user id not found', () => {
  it('reports "User ID NOT found..." when the keyed read misses', async () => {
    getUserMock.mockRejectedValueOnce(
      new ApiError(
        404,
        MSG_USER_ID_NOT_FOUND,
        apiErrorBody(404, 'Not Found', `User ${MISSING_USER_ID} not found`),
      ),
    );
    renderScreen({ handoverUserId: MISSING_USER_ID });

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_USER_ID_NOT_FOUND);
    });
    expect(getUserMock).toHaveBeenCalledWith(MISSING_USER_ID);
    expect(field(LABEL_FIRST_NAME)).toHaveValue('');
    expect(field(LABEL_LAST_NAME)).toHaveValue('');
    expect(field(LABEL_USER_TYPE)).toHaveValue('');
  });
});

describe('UserUpdatePage — required field validation', () => {
  /*
   * COUSR02C re-displayed SEC-USR-PWD in PASSWDO on the ENTER turn, so by the time PF5 ran
   * the field was always populated and its 'Password can NOT be empty...' edit guarded a
   * field the program itself had filled; the field-by-field compare at L227 then found it
   * equal. The credential is a one-way hash here and is deliberately never sent to the
   * client, so the field arrives empty on exactly the turns where the legacy field arrived
   * unchanged. Reproducing the edit literally made a name-only or role-only edit
   * impossible -- and it also put the 'Please modify to update ...' no-change guard out of
   * reach. An empty field is therefore OMITTED from the body rather than sent as "", which
   * the service would read as a request to set a blank credential, and the stored
   * credential is left alone. The literal stays reachable on the ADD screen.
   */
  it('updates the name alone, sending no password, when the field is left empty', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.clear(field(LABEL_FIRST_NAME));
    await user.type(field(LABEL_FIRST_NAME), EDITED_FIRST_NAME);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(MSG_USER_UPDATED);
    });
    expect(updateUserMock).toHaveBeenCalledTimes(1);
    // COUSR02C L169 pre-fills PASSWD from the record and L227-228 rewrites the
    // credential only when the returned value differs, so an update that leaves the
    // password alone must not carry one. A hashed credential cannot be pre-filled, so
    // the property is absent rather than echoed back.
    expect(updateUserMock).toHaveBeenCalledWith(TARGET_USER_ID, {
      firstName: EDITED_FIRST_NAME,
      lastName: STORED_USER.lastName,
      userType: STORED_USER.userType,
    });
    const [, body] = updateUserMock.mock.calls[0];
    expect(body).not.toHaveProperty('password');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByText(MSG_PASSWORD_EMPTY)).toBeNull();
  });

  it('sends the password when one IS typed, so a credential change still works', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(updateUserMock).toHaveBeenCalledTimes(1);
    });
    const [, body] = updateUserMock.mock.calls[0];
    expect(body).toHaveProperty('password', ENTERED_PASSWORD);
    expect(updateUserMock.mock.calls[0]?.[1]).toEqual({
      firstName: STORED_USER.firstName,
      lastName: STORED_USER.lastName,
      userType: STORED_USER.userType,
      password: ENTERED_PASSWORD,
    });
  });

  it('marks the password field as not required, unlike every other entry field', () => {
    // The four fields COUSR02C's EVALUATE actually tests for presence are required; the
    // password is not, because an empty value is a valid statement of "leave it alone".
    void renderScreen();

    expect(field(LABEL_FIRST_NAME)).toHaveAttribute('aria-required', 'true');
    expect(field(LABEL_LAST_NAME)).toHaveAttribute('aria-required', 'true');
    expect(field(LABEL_USER_TYPE)).toHaveAttribute('aria-required', 'true');
    expect(field(LABEL_PASSWORD)).not.toHaveAttribute('aria-required');
  });

  it('treats an all-blank password box as no password rather than as a value', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.clear(field(LABEL_FIRST_NAME));
    await user.type(field(LABEL_FIRST_NAME), EDITED_FIRST_NAME);
    await user.type(field(LABEL_PASSWORD), '   ');
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(updateUserMock).toHaveBeenCalledTimes(1);
    });
    // The service reads a blank value as "not supplied", so sending it would invite a
    // silent no-op; the client applies the same rule and omits the property.
    expect(updateUserMock.mock.calls[0]?.[1]).not.toHaveProperty('password');
  });

  it('reports "First Name can NOT be empty..." when FNAME is cleared', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.clear(field(LABEL_FIRST_NAME));
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_FIRST_NAME_EMPTY);
    });
    expect(updateUserMock).not.toHaveBeenCalled();
  });

  it('reports "Last Name can NOT be empty..." when LNAME is cleared', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.clear(field(LABEL_LAST_NAME));
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_LAST_NAME_EMPTY);
    });
    expect(updateUserMock).not.toHaveBeenCalled();
  });

  it('reports "User Type can NOT be empty..." when USRTYPE is cleared', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.clear(field(LABEL_USER_TYPE));
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_USER_TYPE_EMPTY);
    });
    expect(updateUserMock).not.toHaveBeenCalled();
  });

  it('reports "User ID can NOT be empty..." when ENTER is pressed with a blank USRIDIN', async () => {
    const user = userEvent.setup();
    renderScreen();

    await user.click(pfKey(PF_ENTER_LABEL));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_USER_ID_EMPTY);
    });
    expect(getUserMock).not.toHaveBeenCalled();
  });

  it('reports "User ID can NOT be empty..." when F5 is pressed with a blank USRIDIN', async () => {
    const user = userEvent.setup();
    renderScreen();

    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_USER_ID_EMPTY);
    });
    expect(updateUserMock).not.toHaveBeenCalled();
  });
});

describe('UserUpdatePage — no-change guard', () => {
  it('surfaces "Please modify to update ..." when nothing was modified', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();
    updateUserMock.mockRejectedValueOnce(
      new ApiError(
        400,
        MSG_PLEASE_MODIFY,
        apiErrorBody(400, 'Bad Request', MSG_PLEASE_MODIFY),
      ),
    );

    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(MSG_PLEASE_MODIFY);
    });
    expect(updateUserMock).toHaveBeenCalledWith(TARGET_USER_ID, {
      firstName: STORED_USER.firstName,
      lastName: STORED_USER.lastName,
      userType: STORED_USER.userType,
      password: ENTERED_PASSWORD,
    });
  });
});

describe('UserUpdatePage — save', () => {
  it('sends a UserUpdateRequestDto and reports "<id> has been updated ..."', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    await user.clear(field(LABEL_FIRST_NAME));
    await user.type(field(LABEL_FIRST_NAME), EDITED_FIRST_NAME);
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(MSG_USER_UPDATED);
    });
    const expectedRequest: UserUpdateRequestDto = {
      firstName: EDITED_FIRST_NAME,
      lastName: STORED_USER.lastName,
      userType: STORED_USER.userType,
      password: ENTERED_PASSWORD,
    };
    expect(updateUserMock).toHaveBeenCalledWith(TARGET_USER_ID, expectedRequest);
    expect(updateUserMock).toHaveBeenCalledTimes(1);
  });
});

describe('UserUpdatePage — in-flight duplicate-update guard', () => {
  it('closes every entry field while the update is in flight and writes once for a repeated F5', async () => {
    const user = userEvent.setup();
    let acknowledge!: (value: UserDto) => void;
    updateUserMock.mockReturnValueOnce(
      new Promise<UserDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    await renderFetchedScreen();

    await user.clear(field(LABEL_FIRST_NAME));
    await user.type(field(LABEL_FIRST_NAME), EDITED_FIRST_NAME);
    // ``COUSR02C`` rejects a blank password, so the edit carries one.
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);
    await user.click(pfKey(PF5_LABEL));

    await waitFor(() => {
      expect(updateUserMock).toHaveBeenCalledTimes(1);
    });
    // ``ATTRB=ASKIP`` for the whole in-flight interval: no field, not even the
    // password, can be retyped while the PUT is outstanding.
    for (const label of [
      LABEL_USER_ID,
      LABEL_FIRST_NAME,
      LABEL_LAST_NAME,
      LABEL_PASSWORD,
      LABEL_USER_TYPE,
    ]) {
      expect(field(label)).toBeDisabled();
    }

    // A second F5, from the legend and from the physical key, must not write twice.
    act(() => {
      fireEvent.click(pfKey(PF5_LABEL));
      fireEvent.keyDown(document, { key: 'F5' });
    });
    expect(updateUserMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge({ ...STORED_USER, firstName: EDITED_FIRST_NAME });
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(updateUserMock).toHaveBeenCalledTimes(1);
    expect(field(LABEL_FIRST_NAME)).toBeEnabled();
  });
});

describe('UserUpdatePage — line-24 function keys', () => {
  it('legends ENTER, F3, F4, F5 and F12 exactly as the mapset does', async () => {
    await renderFetchedScreen();

    const toolbar = screen.getByRole('group', { name: 'Function keys' });
    // COUSR02.bms FKEYSC reads
    // 'ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save  F12=Cancel'.
    expect(within(toolbar).getAllByRole('button')).toHaveLength(5);
    expect(pfKey(PF_ENTER_LABEL)).toBeInTheDocument();
    expect(pfKey(PF3_LABEL)).toBeInTheDocument();
    expect(pfKey(PF4_LABEL)).toBeInTheDocument();
    expect(pfKey(PF5_LABEL)).toBeInTheDocument();
    expect(pfKey(PF12_LABEL)).toBeInTheDocument();
  });

  it('fetches the record when the physical ENTER key is pressed', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.type(field(LABEL_USER_ID), TARGET_USER_ID);

    fireEvent.keyDown(document, { key: 'Enter' });

    await waitFor(() => {
      expect(field(LABEL_LAST_NAME)).toHaveValue(STORED_USER.lastName);
    });
    expect(getUserMock).toHaveBeenCalledWith(TARGET_USER_ID);
  });

  it('blanks every field and the message region when F4 is pressed', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);

    fireEvent.keyDown(document, { key: 'F4' });

    await waitFor(() => {
      expect(field(LABEL_USER_ID)).toHaveValue('');
    });
    expect(field(LABEL_FIRST_NAME)).toHaveValue('');
    expect(field(LABEL_LAST_NAME)).toHaveValue('');
    expect(field(LABEL_PASSWORD)).toHaveValue('');
    expect(field(LABEL_USER_TYPE)).toHaveValue('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(updateUserMock).not.toHaveBeenCalled();
  });

  it('saves the record when the physical F5 key is pressed', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);

    fireEvent.keyDown(document, { key: 'F5' });

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(MSG_USER_UPDATED);
    });
    expect(updateUserMock).toHaveBeenCalledTimes(1);
  });

  it('saves the record and returns to the administrator menu when F3 is pressed', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();
    await user.type(field(LABEL_PASSWORD), ENTERED_PASSWORD);

    await user.click(pfKey(PF3_LABEL));

    await waitFor(() => {
      expect(screen.getByTestId(ADMIN_MENU_TEST_ID)).toBeInTheDocument();
    });
    expect(updateUserMock).toHaveBeenCalledWith(TARGET_USER_ID, {
      firstName: STORED_USER.firstName,
      lastName: STORED_USER.lastName,
      userType: STORED_USER.userType,
      password: ENTERED_PASSWORD,
    });
  });

  it('returns the cursor to USRIDIN when the F4 legend button clears the screen', async () => {
    const user = userEvent.setup();
    await renderFetchedScreen();

    // Clicking the legend button focuses it. COUSR02C PF4 re-sends the map and every
    // send honours ATTRB=IC on USRIDIN, so the cursor leaves the key and returns to the
    // key field rather than stranding the operator's next keystroke on a button.
    await user.click(pfKey(PF4_LABEL));

    await waitFor(() => {
      expect(field(LABEL_USER_ID)).toHaveValue('');
    });
    expect(document.activeElement).toBe(field(LABEL_USER_ID));
  });

  it('answers the AIDs the legend does not advertise with the invalid-key literal', async () => {
    await renderFetchedScreen();

    // COUSR02C L127-131: the WHEN OTHER arm publishes CCDA-MSG-INVALID-KEY and
    // re-sends the map, whose ATTRB=IC on USRIDIN returns the cursor. F7 and F8 are the
    // recognised AIDs this screen does not declare.
    for (const key of ['F7', 'F8']) {
      await act(async () => {
        fireEvent.keyDown(document, { key });
        await Promise.resolve();
      });

      expect(screen.getByRole('alert')).toHaveTextContent(CCDA_MSG_INVALID_KEY);
      expect(document.activeElement).toBe(field(LABEL_USER_ID));
      // The fetched record still stands: nothing was re-read and nothing was written.
      expect(field(LABEL_LAST_NAME)).toHaveValue(STORED_USER.lastName);
      expect(updateUserMock).not.toHaveBeenCalled();
    }
  });

  it('claims an unadvertised AID from the browser', async () => {
    await renderFetchedScreen();

    const event = createEvent.keyDown(document, { key: 'F7', cancelable: true });
    fireEvent(document, event);

    expect(event.defaultPrevented).toBe(true);
  });
});
