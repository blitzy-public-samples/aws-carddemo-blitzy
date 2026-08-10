/**
 * :module: UserDeletePage.test
 * :purpose: Verify the administrator delete-user screen ``COUSR03`` (CICS ``CU03``,
 *     program ``app/cbl/COUSR03C.cbl``): the verbatim ``app/bms/COUSR03.bms``
 *     captions and line-24 key legend, the read-for-display fetch by user id
 *     (keyed, or received as router state / a ``userId`` query parameter), the
 *     read-only display of first name, last name and user type, the verbatim
 *     line-23 messages for an empty key and a miss, the DELIBERATE delete —
 *     ``deleteUser`` is never issued by a read and runs only on the explicit
 *     delete / F5 action — and the ENTER / F3 / F4 / F5 function keys, with F3
 *     returning to ``/admin``.
 * :note: ``../api`` is mocked with ``jest.unstable_mockModule`` (the native-ESM
 *     form used by the sibling suites), so no axios instance, no network, and no
 *     Vite ``import.meta`` is touched; ``ApiError`` is published through the mock
 *     so the page, ``useApi``, and this suite share one class for ``instanceof``.
 *     The screen and the shell are imported dynamically after the mock is
 *     registered and share React with the statically imported Testing Library.
 *     The screen is rendered inside ``Layout``, which owns line 23 (message) and
 *     line 24 (key legend), under a ``MemoryRouter`` carrying an ``/admin`` probe
 *     route, with an authenticated administrator established through the shared
 *     session harness, which publishes the identity over the production
 *     ``GET /session`` probe rather than writing the store directly.
 */

import {
  act,
  createEvent,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
// Jest's native-ESM runtime does not inject ``jest`` as a global (unlike
// ``describe`` / ``it`` / ``expect``), so the mock factory is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE02, CCDA_MSG_INVALID_KEY } from '../types';
import type { RenderResult } from '@testing-library/react';
import type { ApiErrorResponse, UserDto } from '../types';

/** BMS line-4 screen heading, also published as the second header title line. */
const HEADING = 'Delete User';

/** BMS line-6 caption of the enterable ``USRIDIN`` key field. */
const LABEL_USER_ID = 'Enter User ID:';

/** BMS line-11 caption of the ``FNAME`` display field. */
const LABEL_FIRST_NAME = 'First Name:';

/** BMS line-13 caption of the ``LNAME`` display field. */
const LABEL_LAST_NAME = 'Last Name:';

/** BMS line-15 caption of the ``USRTYPE`` display field; the trailing space is verbatim. */
const LABEL_USER_TYPE = 'User Type: ';

/** BMS line-15 role legend rendered after ``USRTYPE``. */
const USER_TYPE_HINT = '(A=Admin, U=User)';

/** BMS line-24 legend captions, in ``'ENTER=Fetch  F3=Back  F4=Clear  F5=Delete'`` order. */
const PFKEY_LABEL_ENTER = 'ENTER=Fetch';
const PFKEY_LABEL_PF3 = 'F3=Back';
const PFKEY_LABEL_PF4 = 'F4=Clear';
const PFKEY_LABEL_PF5 = 'F5=Delete';

/** Line-23 text when no user id was keyed (``PROCESS-ENTER-KEY`` / ``DELETE-USER-INFO``). */
const MSG_USER_ID_EMPTY = 'User ID can NOT be empty...';

/** Line-23 text for the ``NOTFND`` branch of the ``USRSEC`` read and delete. */
const MSG_USER_NOT_FOUND = 'User ID NOT found...';

/** Line-23 confirmation prompt shown once the record has been read for display. */
const MSG_PRESS_PF5_DELETE = 'Press PF5 key to delete this user ...';

/** CICS transaction id of the delete-user screen. */
const TRANSACTION_ID = 'CU03';

/** Legacy program name shown on header line 2. */
const PROGRAM_NAME = 'COUSR03C';

/** Route of the delete screen. */
const DELETE_ROUTE = '/users/delete';

/** Route F3 returns to, replacing the legacy ``XCTL`` to ``COADM01C``. */
const ADMIN_ROUTE = '/admin';

/** Test id of the ``/admin`` probe route rendered by the router under test. */
const ADMIN_ROUTE_TESTID = 'admin-menu-route';

/** HTTP status the backend returns for ``RecordNotFoundException``. */
const HTTP_NOT_FOUND = 404;

/** Signed-on administrator (seeded ``security_users`` row ``ADMIN001``, type ``'A'``). */
const ADMIN_USER_ID = 'ADMIN001';

/** Target of the delete workflow (seeded ``security_users`` row ``USER0002``). */
const targetUser: UserDto = {
  userId: 'USER0002',
  firstName: 'AJITH',
  lastName: 'KUMAR',
  userType: 'U',
};

/** User id that no ``USRSEC`` record exists for. */
const MISSING_USER_ID = 'USER9999';

/**
 * :purpose: Build the verbatim delete confirmation
 *     (``STRING 'User ' SEC-USR-ID ' has been deleted ...'``).
 * :param userId: the deleted user id.
 * :returns: the line-23 confirmation text.
 */
function deletedMessage(userId: string): string {
  return `User ${userId} has been deleted ...`;
}

/**
 * :purpose: The ``ApiError`` the mocked ``../api`` publishes, reproducing the
 *     constructor and members of ``frontend/src/api/client.ts`` so the page's
 *     ``error instanceof ApiError`` narrowing and ``useApi``'s error
 *     normalization bind to one shared class.
 * :param status: HTTP status, or ``0`` for a transport failure.
 * :param message: already-resolved error message.
 * :param body: standardized backend error body, when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the HTTP ``409`` conflict.
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

/** ``GET /users/{id}`` — the single-user read for display (``READ-USER-SEC-FILE``). */
const getUserMock = jest.fn<(userId: string) => Promise<UserDto>>();

/** ``DELETE /users/{id}`` — the delete (``DELETE-USER-SEC-FILE``); resolves with no body. */
const deleteUserMock = jest.fn<(userId: string) => Promise<void>>();

/** ``POST /auth/signon`` — a required export of the mocked module: ``useSession`` imports it. */
const signonMock = jest.fn();

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
  deleteUser: deleteUserMock,
  signon: signonMock,
}));

/** Handles for the modules loaded once the ``../api`` mock is registered. */
type UserDeletePageComponent = (typeof import('./UserDeletePage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let UserDeletePage: UserDeletePageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the screen, the shell, and the
  // session store bind to the mocked ``../api``; no module reset, so they share
  // React with Testing Library.
  ({ default: UserDeletePage } = await import('./UserDeletePage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getUserMock.mockReset();
  deleteUserMock.mockReset();
  getUserMock.mockResolvedValue(targetUser);
  deleteUserMock.mockResolvedValue(undefined);
  await seedSignedOnSession(ADMIN_USER_ID, 'A');
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * :purpose: Router entry for the delete screen: a bare path (optionally carrying
 *     the ``userId`` query parameter), or a path plus the navigation state the
 *     user-list screen supplies as ``CDEMO-CU03-USR-SELECTED``.
 */
type DeleteRouteEntry = string | { pathname: string; state: { userId: string } };

/**
 * :purpose: Render the delete screen inside the shared 24x80 shell, under a memory
 *     router that also serves the ``/admin`` probe route so the F3 transfer to the
 *     administrator menu is observable.
 * :param entry: the initial router entry.
 * :returns: the Testing Library render result.
 */
function renderScreen(entry: DeleteRouteEntry): RenderResult {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route
          path={DELETE_ROUTE}
          element={
            <Layout>
              <UserDeletePage />
            </Layout>
          }
        />
        <Route path={ADMIN_ROUTE} element={<div data-testid={ADMIN_ROUTE_TESTID} />} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * :purpose: Key a user id into the ``USRIDIN`` field.
 * :param value: the id to key.
 */
function keyUserId(value: string): void {
  fireEvent.change(screen.getByTestId('user-id'), { target: { value } });
}

/**
 * :purpose: Wait until the record read for display has settled on the whole
 *     screen: the read-only fields carry the record, line 23 carries the
 *     confirmation prompt, and line 24 has been republished with the keys
 *     re-enabled once the read is no longer in flight.
 * :param user: the user expected on the screen.
 */
async function waitForRecordDisplayed(user: UserDto): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('first-name').textContent).toBe(user.firstName);
    expect(infoBanner()).toHaveTextContent(MSG_PRESS_PF5_DELETE);
    expect(screen.getByRole('button', { name: PFKEY_LABEL_PF5 })).toBeEnabled();
  });
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

describe('UserDeletePage — COUSR03 screen chrome and captions', () => {
  it('publishes the CU03 chrome and renders the verbatim COUSR03 captions', () => {
    const { container } = renderScreen(DELETE_ROUTE);

    expect(screen.getByTestId('tran-id')).toHaveTextContent(TRANSACTION_ID);
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(PROGRAM_NAME);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    expect(screen.getByRole('heading', { level: 3, name: HEADING })).toBeInTheDocument();

    expect(screen.getByText(LABEL_USER_ID)).toBeInTheDocument();
    expect(screen.getByText(LABEL_FIRST_NAME)).toBeInTheDocument();
    expect(screen.getByText(LABEL_LAST_NAME)).toBeInTheDocument();
    // Matched without whitespace normalization: the trailing space of
    // ``INITIAL='User Type: '`` is part of the caption.
    expect(
      screen.getByText(LABEL_USER_TYPE, { normalizer: (text) => text }),
    ).toBeInTheDocument();
    expect(screen.getByText(USER_TYPE_HINT)).toBeInTheDocument();

    // Line 23 carries no message on entry, and the shell reflects the seeded
    // administrator session.
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(container.querySelector('.screen')).toHaveAttribute('data-authenticated', 'true');
    expect(getUserMock).not.toHaveBeenCalled();
  });

  it('sizes USRIDIN, FNAME, LNAME and USRTYPE to their COUSR03.CPY widths', () => {
    renderScreen(DELETE_ROUTE);

    // USRIDIN is the one enterable field; FNAME, LNAME and USRTYPE are protected
    // (``DFHBMPRF``) and render as labelled output cells that keep the mapset's
    // column footprint instead of a maxlength.
    expect(screen.getByTestId('user-id')).toHaveAttribute('maxlength', '8');
    expect(screen.getByTestId('first-name').style.minWidth).toBe('20ch');
    expect(screen.getByTestId('last-name').style.minWidth).toBe('20ch');
    expect(screen.getByTestId('user-type').style.minWidth).toBe('1ch');
  });
});

describe('UserDeletePage — read for display', () => {
  it('reads the keyed user id and displays the record read-only', async () => {
    renderScreen(DELETE_ROUTE);

    keyUserId(targetUser.userId);
    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_ENTER }));

    await waitForRecordDisplayed(targetUser);
    expect(getUserMock).toHaveBeenCalledWith(targetUser.userId);
    expect(getUserMock).toHaveBeenCalledTimes(1);

    const firstName = screen.getByTestId('first-name');
    const lastName = screen.getByTestId('last-name');
    const userType = screen.getByTestId('user-type');
    expect(firstName.textContent).toBe(targetUser.firstName);
    expect(lastName.textContent).toBe(targetUser.lastName);
    expect(userType.textContent).toBe(targetUser.userType);
    // The three protected fields are output cells, so nothing about them is
    // enterable: they carry no form value and take no part in the tab order.
    expect(firstName).not.toHaveAttribute('value');
    expect(lastName).not.toHaveAttribute('value');
    expect(userType).not.toHaveAttribute('value');
    expect(firstName.tagName).toBe('DD');
    expect(lastName.tagName).toBe('DD');
    expect(userType.tagName).toBe('DD');
    // ``USRIDIN`` is the only UNPROT field of the map, so it stays enterable.
    expect(screen.getByTestId('user-id')).not.toHaveAttribute('readonly');
  });

  it('reads the user id received as router state on entry', async () => {
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });

    await waitForRecordDisplayed(targetUser);
    expect(getUserMock).toHaveBeenCalledWith(targetUser.userId);
    expect(getUserMock).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('user-id')).toHaveValue(targetUser.userId);
    expect(screen.getByTestId('last-name').textContent).toBe(targetUser.lastName);
  });

  it('reads the user id received as the userId query parameter on entry', async () => {
    renderScreen(`${DELETE_ROUTE}?userId=${targetUser.userId}`);

    await waitForRecordDisplayed(targetUser);
    expect(getUserMock).toHaveBeenCalledWith(targetUser.userId);
    expect(screen.getByTestId('user-id')).toHaveValue(targetUser.userId);
    expect(screen.getByTestId('user-type').textContent).toBe(targetUser.userType);
  });

  it('refuses an empty user id with the verbatim message and issues no read', async () => {
    renderScreen(DELETE_ROUTE);

    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_ENTER }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_USER_ID_EMPTY);
    expect(getUserMock).not.toHaveBeenCalled();

    // A key of only spaces is refused identically.
    keyUserId('   ');
    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_ENTER }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_USER_ID_EMPTY);
    expect(getUserMock).not.toHaveBeenCalled();
  });

  it('surfaces the verbatim not-found message when the read misses', async () => {
    getUserMock.mockRejectedValue(new ApiError(HTTP_NOT_FOUND, 'User not found'));
    renderScreen(DELETE_ROUTE);

    keyUserId(MISSING_USER_ID);
    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_ENTER }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_USER_NOT_FOUND);
    expect(getUserMock).toHaveBeenCalledWith(MISSING_USER_ID);
    expect(screen.getByTestId('first-name').textContent).toBe('');
    expect(screen.getByTestId('last-name').textContent).toBe('');
    expect(screen.getByTestId('user-type').textContent).toBe('');
    expect(deleteUserMock).not.toHaveBeenCalled();
  });
});

describe('UserDeletePage — deliberate delete', () => {
  it('issues no delete on the read and deletes only on the explicit delete action', async () => {
    renderScreen(DELETE_ROUTE);

    keyUserId(targetUser.userId);
    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_ENTER }));
    await waitForRecordDisplayed(targetUser);

    // The read alone never removes the record; line 23 asks for the deliberate key.
    expect(deleteUserMock).not.toHaveBeenCalled();
    expect(infoBanner()).toHaveTextContent(MSG_PRESS_PF5_DELETE);

    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_PF5 }));

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(deletedMessage(targetUser.userId));
    });
    expect(deleteUserMock).toHaveBeenCalledWith(targetUser.userId);
    expect(deleteUserMock).toHaveBeenCalledTimes(1);
    // ``INITIALIZE-ALL-FIELDS`` — the key and the display fields are cleared.
    expect(screen.getByTestId('user-id')).toHaveValue('');
    expect(screen.getByTestId('first-name').textContent).toBe('');
    expect(screen.getByTestId('last-name').textContent).toBe('');
    expect(screen.getByTestId('user-type').textContent).toBe('');
  });

  it('deletes on the F5 key once the record has been read', async () => {
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });
    await waitForRecordDisplayed(targetUser);
    expect(deleteUserMock).not.toHaveBeenCalled();

    fireEvent.keyDown(document, { key: 'F5' });

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(deletedMessage(targetUser.userId));
    });
    expect(deleteUserMock).toHaveBeenCalledWith(targetUser.userId);
    expect(deleteUserMock).toHaveBeenCalledTimes(1);
  });

  it('closes the key field and both actions while the delete is in flight, deleting once', async () => {
    let acknowledge!: () => void;
    deleteUserMock.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        acknowledge = resolve;
      }),
    );
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });
    await waitForRecordDisplayed(targetUser);

    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_PF5 }));

    // ``ATTRB=ASKIP`` for the whole in-flight interval: neither the key field nor
    // either legend action is live while the DELETE is outstanding.
    await waitFor(() => {
      expect(screen.getByTestId('user-id')).toBeDisabled();
    });
    expect(screen.getByRole('button', { name: PFKEY_LABEL_ENTER })).toBeDisabled();
    expect(screen.getByRole('button', { name: PFKEY_LABEL_PF5 })).toBeDisabled();

    // A second delete, from the legend key and from the physical F5, must not delete
    // twice.
    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_PF5 }));
    fireEvent.keyDown(document, { key: 'F5' });
    expect(deleteUserMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge();
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(deleteUserMock).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('user-id')).toBeEnabled();
  });

  it('refuses an empty user id on the delete action and issues no delete', async () => {
    renderScreen(DELETE_ROUTE);

    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_PF5 }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_USER_ID_EMPTY);
    expect(deleteUserMock).not.toHaveBeenCalled();
  });

  it('surfaces the verbatim not-found message when the delete misses', async () => {
    deleteUserMock.mockRejectedValue(new ApiError(HTTP_NOT_FOUND, 'User not found'));
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });
    await waitForRecordDisplayed(targetUser);

    fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_PF5 }));

    expect(await screen.findByRole('alert')).toHaveTextContent(MSG_USER_NOT_FOUND);
    expect(deleteUserMock).toHaveBeenCalledWith(targetUser.userId);
    // The record is not cleared: nothing was deleted.
    expect(screen.getByTestId('user-id')).toHaveValue(targetUser.userId);
  });
});

describe('UserDeletePage — line-24 function keys', () => {
  it('legends exactly the four keys of COUSR03.bms line 24, in order', () => {
    renderScreen(DELETE_ROUTE);

    const keyBar = screen.getByRole('group', { name: 'Function keys' });
    expect(
      within(keyBar)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([PFKEY_LABEL_ENTER, PFKEY_LABEL_PF3, PFKEY_LABEL_PF4, PFKEY_LABEL_PF5]);
  });

  it('reads the record on the ENTER key', async () => {
    renderScreen(DELETE_ROUTE);

    keyUserId(targetUser.userId);
    fireEvent.keyDown(document, { key: 'Enter' });

    await waitForRecordDisplayed(targetUser);
    expect(getUserMock).toHaveBeenCalledWith(targetUser.userId);
    expect(getUserMock).toHaveBeenCalledTimes(1);
  });

  it('clears the key field, the display fields and the message on the F4 key', async () => {
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });
    await waitForRecordDisplayed(targetUser);

    fireEvent.keyDown(document, { key: 'F4' });

    expect(screen.getByTestId('user-id')).toHaveValue('');
    expect(screen.getByTestId('first-name').textContent).toBe('');
    expect(screen.getByTestId('last-name').textContent).toBe('');
    expect(screen.getByTestId('user-type').textContent).toBe('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(deleteUserMock).not.toHaveBeenCalled();
  });

  it('returns to the administrator menu on the F3 key', async () => {
    renderScreen(DELETE_ROUTE);

    fireEvent.keyDown(document, { key: 'F3' });

    expect(await screen.findByTestId(ADMIN_ROUTE_TESTID)).toBeInTheDocument();
    expect(screen.queryByTestId('user-id')).not.toBeInTheDocument();
    expect(deleteUserMock).not.toHaveBeenCalled();
  });

  it('returns the cursor to USRIDIN when the F4 legend button clears the screen', async () => {
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });
    await waitForRecordDisplayed(targetUser);

    // Clicking the legend button focuses it. COUSR03C PF4 re-sends the map and every
    // send honours ATTRB=IC on USRIDIN, so the cursor leaves the key and comes back to
    // the key field; without it the operator's next keystroke goes nowhere.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: PFKEY_LABEL_PF4 }));
      await Promise.resolve();
    });

    expect(screen.getByTestId('user-id')).toHaveValue('');
    expect(document.activeElement).toBe(screen.getByTestId('user-id'));
  });

  it('answers the AIDs the legend does not advertise with the invalid-key literal', async () => {
    renderScreen({ pathname: DELETE_ROUTE, state: { userId: targetUser.userId } });
    await waitForRecordDisplayed(targetUser);

    // COUSR03C L126-130: the WHEN OTHER arm publishes CCDA-MSG-INVALID-KEY and
    // re-sends the map, whose ATTRB=IC on USRIDIN returns the cursor. F7 and F8 are the
    // recognised AIDs this screen does not declare -- PF12 is declared DARK, because
    // COUSR03.bms line 24 legends only four keys while COUSR03C answers F12 as well.
    for (const key of ['F7', 'F8']) {
      await act(async () => {
        fireEvent.keyDown(document, { key });
        await Promise.resolve();
      });

      expect(screen.getByRole('alert')).toHaveTextContent(CCDA_MSG_INVALID_KEY);
      expect(document.activeElement).toBe(screen.getByTestId('user-id'));
      // Nothing was re-read and nothing was deleted: the displayed record stands.
      expect(screen.getByTestId('last-name').textContent).toBe(targetUser.lastName);
      expect(deleteUserMock).not.toHaveBeenCalled();
    }
  });

  it('claims an unadvertised AID from the browser', () => {
    renderScreen(DELETE_ROUTE);

    const event = createEvent.keyDown(document, { key: 'F7', cancelable: true });
    fireEvent(document, event);

    expect(event.defaultPrevented).toBe(true);
  });
});
