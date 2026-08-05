/**
 * :module: UserListPage.test
 * :purpose: Verify the administrator user-list screen ``COUSR00`` (CICS ``CU00``,
 *     program ``app/cbl/COUSR00C.cbl``): the ten-rows-per-page browse fixed by
 *     ``USER-REC OCCURS 10 TIMES``, PF7 / PF8 paging over a 23-user result, the
 *     ``Sel`` column accepting only ``U`` / ``D`` and answering anything else
 *     with ``Invalid selection. Valid values are U and D``, the ``U`` ->
 *     ``/users/update`` and ``D`` -> ``/users/delete`` navigation that replaces
 *     ``XCTL`` to ``COUSR02C`` / ``COUSR03C``, the ``F3=Exit`` / ``F7=Backward``
 *     / ``F8=Forward`` legend with ``F3`` returning to ``/admin``
 *     (``COADM01C``), and the ``Search User ID:`` browse filter.
 * :output: Jest assertions only; the suite writes no artifact.
 * :note: Only ``../api`` is replaced with a ``jest.unstable_mockModule`` double, so
 *     no network call is issued; ``ApiError`` is passed through from the real
 *     ``../api`` so ``useApi`` classifies failures against the production type. The
 *     screen is mounted inside the REAL ``../components/Layout``, so the line-23
 *     message region and the line-24 legend are asserted on the DOM the shell
 *     renders rather than on a captured chrome object; ``react-router`` is real too,
 *     so a transfer is asserted on the route it resolves to. The screen, the shell
 *     and the hook barrel are imported dynamically, after the double is registered,
 *     so all three bind to it.
 */

import { jest } from '@jest/globals';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { ReactElement } from 'react';
import { ApiError } from '../api';
import type {
  ApiErrorResponse,
  SignonRequestDto,
  SignonResponseDto,
  UserListItemDto,
  UserListRequestDto,
  UserListResponseDto,
} from '../types';

/** Verbatim ``COUSR00.bms`` line-24 legend captions, in mapset order. */
const PF_ENTER_LABEL = 'ENTER=Continue';
const PF3_LABEL = 'F3=Back';
const PF7_LABEL = 'F7=Backward';
const PF8_LABEL = 'F8=Forward';

/** Rows the mocked ``listUsers`` returns: more than one screen holds. */
const MOCKED_USER_COUNT = 23;

/** Rows the screen displays per page (``USER-REC OCCURS 10 TIMES``). */
const EXPECTED_ROWS_PER_PAGE = 10;

/** Width of a user id (``SEC-USR-ID`` / ``PIC X(8)``). */
const USER_ID_WIDTH = 8;

/** Digits of the zero-padded ordinal that follows the ``USER`` id prefix. */
const USER_ID_ORDINAL_DIGITS = 4;

/** Zero-based column holding ``User ID``; column 0 holds the ``Sel`` input. */
const USER_ID_COLUMN_INDEX = 1;

/** Digits of a zero-padded row-select field name (``SEL0001``). */
const SELECT_FIELD_DIGITS = 4;

/** Verbatim line-23 text for a ``Sel`` value that is neither ``U`` nor ``D``. */
const INVALID_SELECTION_MESSAGE = 'Invalid selection. Valid values are U and D';

/** ``../api`` double for ``listUsers``, reset before every test. */
const listUsersMock =
  jest.fn<(request: UserListRequestDto) => Promise<UserListResponseDto>>();

/**
 * :purpose: ``../api`` double for ``signon``, the export the ``../hooks`` barrel
 *     resolves through ``useSession``. The screen never calls it.
 */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

/** Records the pathname and router state of every navigation the screen makes. */
const navigationSpy = jest.fn<(pathname: string, state: unknown) => void>();

jest.unstable_mockModule('../api', () => ({
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. ``getSessionIdentity`` is the production
  // ``GET /session`` probe the session harness drives; unanswered by this suite it
  // reports no session, and the harness is the only thing that changes that.
  getSessionIdentity: jest.fn(() => Promise.reject(new Error('No session'))),
  logout: jest.fn(() => Promise.resolve(undefined)),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  listUsers: listUsersMock,
  signon: signonMock,
  ApiError,
}));

type UserListPageComponent = (typeof import('./UserListPage'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');
type UseSessionHook = (typeof import('../hooks'))['useSession'];

let UserListPage: UserListPageComponent;
let Layout: (typeof import('../components/Layout'))['default'];
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];
let useSession: UseSessionHook;

beforeAll(async () => {
  ({ default: UserListPage } = await import('./UserListPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ useSession } = await import('../hooks'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

/**
 * :purpose: Build a contiguous run of list rows shaped like the ``USRSEC``
 *     records the browse returns.
 * :param count: number of rows to build.
 * :returns: rows with ids ``USER0001``..``USER<count>``, matching first / last
 *     names, and every fifth row typed as an administrator (``'A'``).
 */
function buildUsers(count: number): UserListItemDto[] {
  return Array.from({ length: count }, (_unused, index) => {
    const ordinal = index + 1;
    return {
      userId: `USER${String(ordinal).padStart(USER_ID_ORDINAL_DIGITS, '0')}`,
      firstName: `First${ordinal}`,
      lastName: `Last${ordinal}`,
      userType: ordinal % 5 === 0 ? 'A' : 'U',
    };
  });
}

/**
 * :purpose: Wrap rows in the paged response contract the list endpoint returns.
 * :param items: the rows carried by the response.
 * :param hasNext: server-side forward-paging flag (``nextPage``).
 * :param _hasPrevious: retained for call-site readability; the delivered
 *     contract carries no backward-paging flag, the page number carries it.
 * :param pageNumber: one-based server page number.
 * :returns: the ``UserListResponseDto`` for those rows.
 */
function pagedResponse(
  items: UserListItemDto[],
  hasNext = false,
  _hasPrevious = false,
  pageNumber = 1,
): UserListResponseDto {
  return {
    users: items,
    pageNumber,
    userIdFirst: items.length === 0 ? null : items[0].userId,
    userIdLast: items.length === 0 ? null : items[items.length - 1].userId,
    nextPage: hasNext,
    selectedUserId: null,
    selectedAction: null,
    message: null,
  };
}

/**
 * :purpose: Route element that records the location it was reached with, giving
 *     the suite a navigation spy while ``useNavigate`` stays the real router
 *     implementation.
 * :param testId: ``data-testid`` published for the destination screen.
 * :returns: the placeholder destination screen.
 */
function RouteRecorder({ testId }: { testId: string }): ReactElement {
  const location = useLocation();
  navigationSpy(location.pathname, location.state);
  return <div data-testid={testId} />;
}

/**
 * :purpose: Publish the seeded session role so the administrator-only premise of
 *     the screen is observable.
 * :returns: the seeded role and administrator flag.
 */
function SessionProbe(): ReactElement {
  const { role, isAdmin } = useSession();
  return (
    <div>
      <span data-testid="session-role">{role ?? ''}</span>
      <span data-testid="session-is-admin">{String(isAdmin)}</span>
    </div>
  );
}

/**
 * :purpose: Render the screen at ``/users`` with the four routes its workflow
 *     reaches, and settle the browse request issued on mount.
 * :returns: a promise that resolves once the first response is applied.
 */
async function renderScreen(): Promise<void> {
  await act(async () => {
    render(
      <MemoryRouter initialEntries={['/users']}>
        <Layout>
          <Routes>
            <Route
              path="/users"
              element={
                <>
                  <SessionProbe />
                  <UserListPage />
                </>
              }
            />
            <Route
              path="/users/update"
              element={<RouteRecorder testId="update-user-screen" />}
            />
            <Route
              path="/users/delete"
              element={<RouteRecorder testId="delete-user-screen" />}
            />
            <Route
              path="/admin"
              element={<RouteRecorder testId="admin-menu-screen" />}
            />
          </Routes>
        </Layout>
      </MemoryRouter>,
    );
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Read the line-23 error region the shared shell renders.
 * :returns: the message text, or the empty string when line 23 carries none.
 */
function errorText(): string {
  return screen.queryByRole('alert')?.textContent ?? '';
}

/**
 * :purpose: Read the line-23 informational region the shared shell renders. The shell
 *     also renders a visually hidden ``role="status"`` busy announcer, so the banner
 *     is matched on its own class rather than on the role alone.
 * :returns: the informational text, or the empty string when line 23 carries none.
 */
function infoText(): string {
  const banner = document.querySelector<HTMLElement>('.errorBanner[role="status"]');
  return banner?.textContent ?? '';
}

/**
 * :purpose: The line-24 legend the shared shell renders from the chrome the screen
 *     published, in publication order.
 * :returns: one legend caption per rendered function key.
 */
function legendLabels(): (string | null)[] {
  const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });
  return within(toolbar)
    .getAllByRole('button')
    .map((key) => key.textContent);
}

/**
 * :purpose: Locate a line-24 function key by its legend caption.
 * :param label: the verbatim legend caption.
 * :returns: the function-key button the shell renders.
 */
function pfKeyButton(label: string): HTMLElement {
  return screen.getByRole('button', { name: label });
}

/**
 * :purpose: Activate a function key through the legend button the shell renders and
 *     settle the resulting state.
 * :param label: the verbatim legend caption.
 * :returns: a promise that resolves once React has flushed the update.
 */
async function activatePfKey(label: string): Promise<void> {
  await act(async () => {
    fireEvent.click(pfKeyButton(label));
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns — including the
    // chrome the screen republishes into the shell for the outcome.
    await Promise.resolve();
  });
}

/**
 * :purpose: Read the user ids of the rows currently displayed.
 * :returns: the ``User ID`` column value of every rendered data row, in screen
 *     order. Column 0 holds the ``Sel`` input, so column 1 is read.
 */
function displayedUserIds(): string[] {
  return screen
    .getAllByTestId('user-row')
    .map(
      (row) =>
        within(row).getAllByRole('cell')[USER_ID_COLUMN_INDEX].textContent ?? '',
    );
}

/**
 * :purpose: Press the line-24 ENTER key the shared shell renders and settle the
 *     submission.
 * :returns: a promise that resolves once the submission has settled.
 */
async function pressEnter(): Promise<void> {
  await activatePfKey(PF_ENTER_LABEL);
}

/**
 * :purpose: Type a ``Sel`` value against one row and press ENTER.
 * :param userId: the user id of the row to select.
 * :param value: the ``Sel`` character to type.
 * :returns: a promise that resolves once the submission — and the chrome the screen
 *     republishes into the shell for its outcome — has settled.
 */
async function selectRowAndEnter(userId: string, value: string): Promise<void> {
  await act(async () => {
    fireEvent.change(screen.getByLabelText(`Select user ${userId}`), {
      target: { value },
    });
    await Promise.resolve();
  });
  await activatePfKey(PF_ENTER_LABEL);
}

/** Verbatim ``COUSR00C`` PF7 boundary message. */
const ALREADY_TOP_MESSAGE = 'You are already at the top of the page...';

/** Verbatim ``COUSR00C`` PF8 boundary message. */
const ALREADY_BOTTOM_MESSAGE = 'You are already at the bottom of the page...';

/**
 * :purpose: Emulate the cursor-driven browse ``COUSR00C`` performs. The screen never
 *     slices rows itself: it renders the page the server answered with, so the mock
 *     answers ten rows from the requested cursor and reports whether further rows
 *     exist in each direction.
 * :param request: the browse request the screen issued.
 * :returns: the page the server answers with.
 */
function browse(request: UserListRequestDto): UserListResponseDto {
  const all = buildUsers(MOCKED_USER_COUNT);
  const startId = request.userId ?? '';
  const rows = startId === '' ? all : all.filter((row) => row.userId >= startId);
  let from = 0;
  if (request.cursor !== undefined) {
    const at = rows.findIndex((row) => row.userId === request.cursor);
    if (request.direction === 'PF8') {
      from = at + 1;
    } else if (request.direction === 'PF7') {
      from = Math.max(at - EXPECTED_ROWS_PER_PAGE, 0);
    }
  }
  const slice = rows.slice(from, from + EXPECTED_ROWS_PER_PAGE);
  return pagedResponse(
    slice,
    from + slice.length < rows.length,
    from > 0,
    Math.floor(from / EXPECTED_ROWS_PER_PAGE) + 1,
  );
}

beforeEach(async () => {
  listUsersMock.mockReset();
  signonMock.mockReset();
  navigationSpy.mockReset();
  listUsersMock.mockImplementation((request) => Promise.resolve(browse(request)));
  await seedSignedOnSession('ADMIN001', 'A');
});

afterEach(async () => {
  await seedSignedOutSession();
});

describe('UserListPage — COUSR00 screen contract', () => {
  it('renders the List Users heading, browse filter, and column headings', async () => {
    await renderScreen();

    expect(
      screen.getByRole('heading', { level: 2, name: 'List Users' }),
    ).toBeInTheDocument();

    const searchField = screen.getByLabelText('Search User ID:');
    expect(searchField).toHaveAttribute('id', 'USRIDIN');
    expect(searchField).toHaveAttribute('name', 'USRIDIN');
    expect(searchField).toHaveAttribute('maxlength', String(USER_ID_WIDTH));
    expect(searchField).toHaveValue('');

    const table = screen.getByTestId('user-list-table');
    const headings = within(table).getAllByRole('columnheader');
    expect(headings.map((heading) => heading.textContent)).toEqual([
      'Sel',
      'User ID',
      'First Name',
      'Last Name',
      'Type',
    ]);
    expect(
      screen.getByText(
        "Type 'U' to Update or 'D' to Delete a User from the list",
      ),
    ).toBeInTheDocument();
  });

  it('publishes the CU00 / COUSR00C chrome with the List Users title', async () => {
    await renderScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CU00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COUSR00C');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    // The screen name lives in the body heading (BMS row 4), not in title02.
    expect(screen.getByRole('heading', { level: 2, name: 'List Users' })).toBeInTheDocument();
    expect(errorText()).toBe('');
    expect(infoText()).toBe('');
  });

  it('requests the first page of the unfiltered browse on entry', async () => {
    await renderScreen();

    expect(listUsersMock).toHaveBeenCalledTimes(1);
    expect(listUsersMock).toHaveBeenCalledWith({ page: 1 });
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
  });
});

describe('UserListPage — ten rows per page (USER-REC OCCURS 10 TIMES)', () => {
  it('displays exactly 10 data rows on page 1 out of 23 available users', async () => {
    await renderScreen();

    expect(screen.getAllByTestId('user-row')).toHaveLength(10);
    expect(screen.getAllByTestId('user-row')).toHaveLength(EXPECTED_ROWS_PER_PAGE);
  });

  it('fills the ten rows with the first ten users in browse order', async () => {
    await renderScreen();

    expect(displayedUserIds()).toEqual([
      'USER0001',
      'USER0002',
      'USER0003',
      'USER0004',
      'USER0005',
      'USER0006',
      'USER0007',
      'USER0008',
      'USER0009',
      'USER0010',
    ]);
  });

  it('maps every row field of the symbolic map onto its column', async () => {
    await renderScreen();

    const cells = within(screen.getAllByTestId('user-row')[4]).getAllByRole(
      'cell',
    );
    expect(cells[1]).toHaveTextContent('USER0005');
    expect(cells[2]).toHaveTextContent('First5');
    expect(cells[3]).toHaveTextContent('Last5');
    expect(cells[4]).toHaveTextContent('A');
  });

  it('names the row select fields SEL0001 through SEL0010', async () => {
    await renderScreen();

    const displayed = displayedUserIds();
    displayed.forEach((userId, index) => {
      const expectedName = `SEL${String(index + 1).padStart(
        SELECT_FIELD_DIGITS,
        '0',
      )}`;
      const field = screen.getByLabelText(`Select user ${userId}`);
      expect(field).toHaveAttribute('id', expectedName);
      expect(field).toHaveAttribute('name', expectedName);
      expect(field).toHaveAttribute('maxlength', '1');
    });
    expect(screen.getByLabelText('Select user USER0001')).toHaveAttribute(
      'id',
      'SEL0001',
    );
    expect(screen.getByLabelText('Select user USER0010')).toHaveAttribute(
      'id',
      'SEL0010',
    );
  });

  it('displays no data row when the browse returns nothing', async () => {
    listUsersMock.mockResolvedValue(pagedResponse([]));
    await renderScreen();

    expect(screen.queryAllByTestId('user-row')).toHaveLength(0);
    // Every AID reaches COUSR00C, which answers a boundary with its own message
    // rather than ignoring the key, so neither legend entry is ever withdrawn.
    expect(pfKeyButton(PF7_LABEL)).toBeEnabled();
    expect(pfKeyButton(PF8_LABEL)).toBeEnabled();

    await activatePfKey(PF7_LABEL);
    expect(errorText()).toBe(ALREADY_TOP_MESSAGE);
    expect(listUsersMock).toHaveBeenCalledTimes(1);
  });
});

describe('UserListPage — PF7 / PF8 paging', () => {
  it('pages forward with F8 to rows 11 through 20', async () => {
    await renderScreen();

    await activatePfKey(PF8_LABEL);

    expect(screen.getAllByTestId('user-row')).toHaveLength(10);
    expect(displayedUserIds()).toEqual([
      'USER0011',
      'USER0012',
      'USER0013',
      'USER0014',
      'USER0015',
      'USER0016',
      'USER0017',
      'USER0018',
      'USER0019',
      'USER0020',
    ]);
    expect(screen.getByTestId('page-number')).toHaveTextContent('2');
  });

  it('pages backward with F7 to the first ten rows', async () => {
    await renderScreen();

    await activatePfKey(PF8_LABEL);
    await activatePfKey(PF7_LABEL);

    expect(displayedUserIds()[0]).toBe('USER0001');
    expect(displayedUserIds()).toHaveLength(10);
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
  });

  it('answers F7 on page 1 with the top-of-page message and no browse', async () => {
    await renderScreen();

    await activatePfKey(PF7_LABEL);

    expect(errorText()).toBe(ALREADY_TOP_MESSAGE);
    expect(listUsersMock).toHaveBeenCalledTimes(1);
    expect(displayedUserIds()[0]).toBe('USER0001');

    await activatePfKey(PF8_LABEL);

    expect(displayedUserIds()[0]).toBe('USER0011');
    expect(errorText()).toBe('');
  });

  it('answers F8 on the last page, which holds the remaining three rows', async () => {
    await renderScreen();

    await activatePfKey(PF8_LABEL);
    await activatePfKey(PF8_LABEL);

    expect(displayedUserIds()).toEqual(['USER0021', 'USER0022', 'USER0023']);
    expect(screen.getByTestId('page-number')).toHaveTextContent('3');

    await activatePfKey(PF8_LABEL);

    expect(errorText()).toBe(ALREADY_BOTTOM_MESSAGE);
    expect(displayedUserIds()).toEqual(['USER0021', 'USER0022', 'USER0023']);
    expect(listUsersMock).toHaveBeenCalledTimes(3);
  });

  it('continues the browse on the server when it reports a further page', async () => {
    listUsersMock.mockResolvedValue(
      pagedResponse(buildUsers(EXPECTED_ROWS_PER_PAGE), true),
    );
    await renderScreen();

    await activatePfKey(PF8_LABEL);

    expect(listUsersMock).toHaveBeenCalledTimes(2);
    // The forward browse names the AID and the last id it displayed, exactly as
    // ``PROCESS-PF8-KEY`` continues the ``STARTBR`` from ``USR-SEL-ID-LAST``.
    expect(listUsersMock).toHaveBeenLastCalledWith({
      direction: 'PF8',
      cursor: `USER00${String(EXPECTED_ROWS_PER_PAGE)}`,
    });
  });
});

describe('UserListPage — Sel column accepts only U and D', () => {
  it('rejects any other character with the verbatim line-23 message', async () => {
    await renderScreen();

    await selectRowAndEnter('USER0003', 'X');

    expect(errorText()).toBe(INVALID_SELECTION_MESSAGE);
    expect(errorText()).toBe(
      'Invalid selection. Valid values are U and D',
    );
    expect(navigationSpy).not.toHaveBeenCalled();
    expect(screen.getByTestId('user-list-table')).toBeInTheDocument();
  });

  it('rejects a numeric selection with the same message', async () => {
    await renderScreen();

    await selectRowAndEnter('USER0007', '1');

    expect(errorText()).toBe(INVALID_SELECTION_MESSAGE);
    expect(navigationSpy).not.toHaveBeenCalled();
  });

  it('publishes no message when no row is selected', async () => {
    await renderScreen();

    await pressEnter();

    expect(errorText()).toBe('');
    expect(navigationSpy).not.toHaveBeenCalled();
  });
});

describe('UserListPage — U and D row navigation', () => {
  it('navigates U to /users/update carrying the selected user id', async () => {
    await renderScreen();

    await selectRowAndEnter('USER0003', 'U');

    expect(navigationSpy).toHaveBeenCalledWith('/users/update', {
      userId: 'USER0003',
    });
    expect(screen.getByTestId('update-user-screen')).toBeInTheDocument();
  });

  it('navigates D to /users/delete carrying the selected user id', async () => {
    await renderScreen();

    await selectRowAndEnter('USER0006', 'D');

    expect(navigationSpy).toHaveBeenCalledWith('/users/delete', {
      userId: 'USER0006',
    });
    expect(screen.getByTestId('delete-user-screen')).toBeInTheDocument();
  });

  it('accepts the lower-case selections u and d', async () => {
    await renderScreen();

    await selectRowAndEnter('USER0002', 'u');

    expect(navigationSpy).toHaveBeenCalledWith('/users/update', {
      userId: 'USER0002',
    });

    navigationSpy.mockReset();
    await renderScreen();

    await selectRowAndEnter('USER0004', 'd');

    expect(navigationSpy).toHaveBeenCalledWith('/users/delete', {
      userId: 'USER0004',
    });
  });

  it('carries the user id of a row selected on the second page', async () => {
    await renderScreen();

    await activatePfKey(PF8_LABEL);
    await selectRowAndEnter('USER0014', 'U');

    expect(navigationSpy).toHaveBeenCalledWith('/users/update', {
      userId: 'USER0014',
    });
  });
});

describe('UserListPage — function keys', () => {
  it('publishes the ENTER / F3 / F7 / F8 legend with its verbatim labels', async () => {
    await renderScreen();

    // COUSR00.bms line 24 reads
    // 'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'.
    expect(legendLabels()).toEqual([
      PF_ENTER_LABEL,
      PF3_LABEL,
      PF7_LABEL,
      PF8_LABEL,
    ]);
    // Every AID reaches COUSR00C, which answers a boundary with its own message, so
    // no legend entry is ever withdrawn.
    expect(pfKeyButton(PF3_LABEL)).toBeEnabled();
    expect(pfKeyButton(PF7_LABEL)).toBeEnabled();
    expect(pfKeyButton(PF8_LABEL)).toBeEnabled();
  });

  it('exits to the administrator menu on F3', async () => {
    await renderScreen();

    await activatePfKey(PF3_LABEL);

    expect(navigationSpy).toHaveBeenCalledWith('/admin', null);
    expect(screen.getByTestId('admin-menu-screen')).toBeInTheDocument();
  });

  it('clears the selection message when paging', async () => {
    await renderScreen();

    await selectRowAndEnter('USER0003', 'X');
    expect(errorText()).toBe(INVALID_SELECTION_MESSAGE);

    await activatePfKey(PF8_LABEL);

    expect(errorText()).toBe('');
  });
});

describe('UserListPage — Search User ID filter', () => {
  it('repositions the browse from the filter field on ENTER', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search User ID:'), 'USER0005');
    await pressEnter();

    expect(listUsersMock).toHaveBeenCalledTimes(2);
    expect(listUsersMock).toHaveBeenLastCalledWith({
      userId: 'USER0005',
      page: 1,
    });
  });

  it('restarts the browse at the first page after a filter is applied', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await activatePfKey(PF8_LABEL);
    expect(screen.getByTestId('page-number')).toHaveTextContent('2');

    await user.type(screen.getByLabelText('Search User ID:'), 'USER0002');
    await pressEnter();

    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
    // The filter is the ``STARTBR`` key, so the restarted browse opens on it.
    expect(displayedUserIds()[0]).toBe('USER0002');
  });
});

describe('UserListPage — failed browse', () => {
  it('publishes the backend message of a failed list request', async () => {
    const body: ApiErrorResponse = {
      timestamp: '2026-08-05T00:00:00Z',
      status: 404,
      error: 'Not Found',
      message: 'User ID NOT found...',
      path: '/users',
    };
    listUsersMock.mockRejectedValue(
      new ApiError(404, 'Request failed with status code 404', body),
    );
    await renderScreen();

    expect(errorText()).toBe('User ID NOT found...');
    expect(screen.queryAllByTestId('user-row')).toHaveLength(0);
  });

  it('falls back to the client message when the response carries no body', async () => {
    listUsersMock.mockRejectedValue(new ApiError(0, 'Network Error'));
    await renderScreen();

    expect(errorText()).toBe('Network Error');
  });
});

describe('UserListPage — administrator-only screen', () => {
  it('renders the browse for a session seeded with the administrator role', async () => {
    await renderScreen();

    expect(screen.getByTestId('session-role')).toHaveTextContent('A');
    expect(screen.getByTestId('session-is-admin')).toHaveTextContent('true');
    expect(screen.getAllByTestId('user-row')).toHaveLength(
      EXPECTED_ROWS_PER_PAGE,
    );
  });
});

