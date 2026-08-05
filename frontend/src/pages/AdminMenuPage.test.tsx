/**
 * AdminMenuPage screen tests.
 *
 * :purpose: Verify the administrator-menu workflow that replaces BMS mapset
 *     ``COADM01`` (map ``COADM1A``) and program ``COADM01C`` under CICS
 *     transaction ``CA00``: the served admin options fill the mapset option
 *     slots, the ``OPTION`` field accepts two numeric positions
 *     (``OPTIONI PIC X(2)``, ``ATTRB=NUM``), every valid selection routes to its
 *     administration screen, an invalid or blank entry raises the verbatim
 *     ``PROCESS-ENTER-KEY`` message in the line-23 region, and the line-24
 *     legend carries ``ENTER=Continue`` and ``F3=Exit``.
 * :output: Jest assertions only; no artifacts are produced.
 * :note: ``../api`` and ``react-router`` are replaced through
 *     ``jest.unstable_mockModule``: ``getAdminMenu`` is served from a fixture,
 *     the real ``ApiError`` class is passed through, and ``useNavigate`` yields a
 *     spy. The page and the shell are imported after those registrations. The
 *     page is mounted inside :func:`Layout`, which renders the line-23 message
 *     region and the line-24 legend from the chrome the page publishes.
 *     Rationale is recorded in ``docs/decision-log.md``.
 */
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
// Bound statically, so these resolve to the real module before the mock below is
// registered; the mock then re-exports them with only ``useNavigate`` replaced, so
// the router context the shell reads is the one ``MemoryRouter`` provides.
import { MemoryRouter as ActualMemoryRouter, useLocation } from 'react-router';
import { ApiError } from '../api';
import type {
  MenuOption,
  MenuResponseDto,
  MenuSelectionRequestDto,
  MenuSelectionResponseDto,
  Role,
} from '../types';

/** Administrator user id seeded into the session store (``CDEMO-USER-ID``). */
const ADMIN_USER_ID = 'ADMIN001';

/** Administrator role code (COMMAREA ``88 CDEMO-USRTYP-ADMIN VALUE 'A'``). */
const ADMIN_ROLE: Role = 'A';

/** Route the admin menu is mounted on. */
const ADMIN_MENU_ROUTE = '/admin';

/**
 * Route ``F3=Exit`` returns to. ``COADM01C`` L96-98 moves ``'COSGN00C'`` to
 * ``CDEMO-TO-PROGRAM`` and performs ``RETURN-TO-SIGNON-SCREEN``, so PF3 leaves the
 * application rather than dropping to the main menu.
 */
const SIGNON_ROUTE = '/signon';

/** Row-4 screen heading (BMS ``INITIAL='Admin Menu'``). */
const SCREEN_HEADING = 'Admin Menu';

/** Level the row-4 screen heading renders at; the header title02 also reads ``Admin Menu``. */
const SCREEN_HEADING_LEVEL = 2;

/** Row-20 prompt that names the ``OPTION`` entry field (BMS ``COLOR=TURQUOISE``). */
const OPTION_FIELD_LABEL = 'Please select an option :';

/** Width of the ``OPTION`` entry field (``OPTIONI PIC X(2)``). */
const OPTION_MAX_LENGTH = 2;

/** Line-24 legend text of the ENTER action. */
const ENTER_KEY_LABEL = 'ENTER=Continue';

/** Line-24 legend text of the PF3 action. */
const EXIT_KEY_LABEL = 'F3=Exit';

/** Verbatim ``COADM01C`` ``PROCESS-ENTER-KEY`` invalid-option message. */
const INVALID_OPTION_MESSAGE = 'Please enter a valid option number...';

/** Status of the rejected ``getAdminMenu`` load exercised by the failure case. */
const FORBIDDEN_STATUS = 403;

/** Message carried by that rejected ``getAdminMenu`` load. */
const FORBIDDEN_MESSAGE = 'Access is denied';

/**
 * ``GET /admin/menu`` payload for transaction ``CA00``, mirroring the four
 * populated rows of ``app/cpy/COADM02Y.cpy``. Option names keep the legacy
 * ``PIC X(35)`` padding the gateway serializes.
 */
const ADMIN_MENU_RESPONSE: MenuResponseDto = {
  tranId: 'CA00',
  programName: 'COADM01C',
  options: [
    {
      optionNumber: 1,
      optionName: 'User List (Security)               ',
      programName: 'COUSR00C',
      targetRoute: '/users',
    },
    {
      optionNumber: 2,
      optionName: 'User Add (Security)                ',
      programName: 'COUSR01C',
      targetRoute: '/users',
    },
    {
      optionNumber: 3,
      optionName: 'User Update (Security)             ',
      programName: 'COUSR02C',
      targetRoute: '/users',
    },
    {
      optionNumber: 4,
      optionName: 'User Delete (Security)             ',
      programName: 'COUSR03C',
      targetRoute: '/users',
    },
  ],
  message: null,
};

/**
 * Option number, the program the gateway dispatches it to, and the administration
 * screen that program's route resolves to.
 */
const ADMIN_OPTION_ROUTES: [number, string, string][] = [
  [1, 'COUSR00C', '/users'],
  [2, 'COUSR01C', '/users/add'],
  [3, 'COUSR02C', '/users/update'],
  [4, 'COUSR03C', '/users/delete'],
];

/** Entries the ``PROCESS-ENTER-KEY`` validation rejects, described then typed. */
const REJECTED_OPTIONS: [string, string][] = [
  ['an out-of-range option', '9'],
  ['a zeros option', '0'],
  ['a blank option', ''],
];

/** Spy standing in for the ``useNavigate`` result the page dispatches through. */
const navigateMock = jest.fn<(to: string) => void>();

/** Mocked ``GET /admin/menu`` call the page loads its option slots from. */
const getAdminMenuMock = jest.fn<() => Promise<MenuResponseDto>>();

/**
 * Mocked ``POST /admin/menu/select`` call. ``COADM01C`` ``PROCESS-ENTER-KEY`` runs on
 * the gateway, so the option edits and the dispatched program come from the server and
 * the screen only resolves that program to its own route.
 */
const selectAdminMenuOptionMock =
  jest.fn<
    (request: MenuSelectionRequestDto) => Promise<MenuSelectionResponseDto>
  >();

/**
 * :purpose: Build the gateway outcome for a dispatched option.
 * :param programName: program the gateway transferred to.
 * :returns: the ``MenuSelectionResponseDto`` for that transfer.
 */
function dispatched(programName: string): MenuSelectionResponseDto {
  return { dispatched: true, programName, targetRoute: null, message: null };
}

/**
 * :purpose: Build the gateway outcome for an option ``PROCESS-ENTER-KEY`` refused.
 * :param message: the verbatim line-23 message the program moves.
 * :returns: the refusing ``MenuSelectionResponseDto``.
 */
function refused(message: string): MenuSelectionResponseDto {
  return { dispatched: false, programName: null, targetRoute: null, message };
}

// The mocked barrel exposes the real ``ApiError`` class ``useApi`` narrows
// against, the fixture-backed ``getAdminMenu``, and the ``signon`` binding
// ``useSession`` imports.
jest.unstable_mockModule('../api', () => ({
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  selectAdminMenuOption: selectAdminMenuOptionMock,
  __esModule: true,
  ApiError,
  getAdminMenu: getAdminMenuMock,
  signon: jest.fn(),
}));

jest.unstable_mockModule('react-router', () => ({
  __esModule: true,
  MemoryRouter: ActualMemoryRouter,
  useLocation,
  useNavigate: () => navigateMock,
}));

type AdminMenuPageComponent = (typeof import('./AdminMenuPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type MemoryRouterComponent = (typeof import('react-router'))['MemoryRouter'];
type SetSessionSeam = (typeof import('../hooks/useSession'))['__setSession'];

let AdminMenuPage: AdminMenuPageComponent;
let Layout: LayoutComponent;
let MemoryRouter: MemoryRouterComponent;
let setSession: SetSessionSeam;

/**
 * :purpose: Build the option row text the page renders for one served option
 *     (``CDEMO-ADMIN-OPT-NUM`` + ``'. '`` + ``CDEMO-ADMIN-OPT-NAME``), with the
 *     legacy padding collapsed the way the DOM text comparison normalizes it.
 * :param option: the served menu option.
 * :returns: the expected row text.
 */
function optionLabel(option: MenuOption): string {
  return `${String(option.optionNumber)}. ${option.optionName.trim()}`;
}

/**
 * :purpose: Mount the admin menu inside the routed shared screen shell, which
 *     renders the line-23 message region and the line-24 legend from the chrome
 *     the page publishes.
 */
function mountAdminMenu(): void {
  render(
    <MemoryRouter initialEntries={[ADMIN_MENU_ROUTE]}>
      <Layout>
        <AdminMenuPage />
      </Layout>
    </MemoryRouter>,
  );
}

/**
 * :purpose: Mount the admin menu and wait for the options served by
 *     ``getAdminMenu`` to reach the option slots.
 * :returns: a promise resolving once the first option row is rendered.
 */
async function renderAdminMenu(): Promise<void> {
  mountAdminMenu();
  await screen.findByText(optionLabel(ADMIN_MENU_RESPONSE.options[0]));
}

/**
 * :purpose: Locate the ``OPTION`` entry field of the mounted screen.
 * :returns: the ``OPTION`` input element.
 */
function optionField(): HTMLElement {
  return screen.getByRole('textbox', { name: OPTION_FIELD_LABEL });
}

/**
 * :purpose: Type an option into the ``OPTION`` field, mirroring a 3270 entry.
 * :param value: the raw text typed into the field.
 */
function typeOption(value: string): void {
  fireEvent.change(optionField(), { target: { value } });
}

/**
 * :purpose: Press the line-24 ``ENTER=Continue`` key of the mounted screen.
 */
function pressEnterKey(): void {
  fireEvent.click(screen.getByRole('button', { name: ENTER_KEY_LABEL }));
}

beforeAll(async () => {
  // Loaded after the mock registrations, without a module reset, so the graph
  // binds to the mocks and shares React with Testing Library.
  ({ default: AdminMenuPage } = await import('./AdminMenuPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ MemoryRouter } = await import('react-router'));
  ({ __setSession: setSession } = await import('../hooks/useSession'));
});

beforeEach(() => {
  navigateMock.mockReset();
  getAdminMenuMock.mockReset();
  getAdminMenuMock.mockResolvedValue(ADMIN_MENU_RESPONSE);
  selectAdminMenuOptionMock.mockReset();
  act(() => {
    setSession(ADMIN_USER_ID, ADMIN_ROLE);
  });
});

afterEach(() => {
  act(() => {
    setSession(null, null);
  });
});

describe('AdminMenuPage', () => {
  it('loads the admin menu and renders the heading and served options', async () => {
    await renderAdminMenu();

    expect(getAdminMenuMock).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole('heading', { level: SCREEN_HEADING_LEVEL, name: SCREEN_HEADING }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('tran-id')).toHaveTextContent(ADMIN_MENU_RESPONSE.tranId);
    expect(screen.getByTestId('pgm-name')).toHaveTextContent(
      ADMIN_MENU_RESPONSE.programName,
    );

    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(ADMIN_MENU_RESPONSE.options.length);
    ADMIN_MENU_RESPONSE.options.forEach((option, index) => {
      expect(rows[index]).toHaveTextContent(optionLabel(option));
    });
  });

  it('surfaces the ApiError message in the line-23 region when the load fails', async () => {
    getAdminMenuMock.mockRejectedValue(
      new ApiError(FORBIDDEN_STATUS, FORBIDDEN_MESSAGE),
    );

    mountAdminMenu();

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe(FORBIDDEN_MESSAGE);
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });

  it('renders the OPTION field as a two-position numeric entry', async () => {
    await renderAdminMenu();

    const field = optionField();
    expect(field).toHaveAttribute('maxLength', String(OPTION_MAX_LENGTH));
    expect(field).toHaveAttribute('inputMode', 'numeric');

    typeOption('12');
    expect(field).toHaveValue('12');

    // ``ATTRB=NUM`` accepts digits only, and ``OPTIONI`` holds two of them.
    typeOption('1x34');
    expect(field).toHaveValue('13');
    typeOption('AB');
    expect(field).toHaveValue('');
  });

  it.each(ADMIN_OPTION_ROUTES)(
    'routes admin option %i, dispatched to %s, to %s',
    async (option, programName, route) => {
      selectAdminMenuOptionMock.mockResolvedValue(dispatched(programName));
      await renderAdminMenu();

      typeOption(String(option));
      pressEnterKey();

      await waitFor(() => {
        expect(navigateMock).toHaveBeenCalledTimes(1);
      });
      expect(selectAdminMenuOptionMock).toHaveBeenCalledWith({
        option: String(option),
        aid: 'ENTER',
      });
      expect(navigateMock).toHaveBeenCalledWith(route);
    },
  );

  it('submits the entered option when ENTER is pressed in the OPTION field', async () => {
    selectAdminMenuOptionMock.mockResolvedValue(dispatched('COUSR00C'));
    await renderAdminMenu();

    typeOption('1');
    fireEvent.keyDown(optionField(), { key: 'Enter' });

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledTimes(1);
    });
    expect(navigateMock).toHaveBeenCalledWith('/users');
  });

  it.each(REJECTED_OPTIONS)(
    'rejects %s with the verbatim COADM01C message',
    async (_description, option) => {
      selectAdminMenuOptionMock.mockResolvedValue(refused(INVALID_OPTION_MESSAGE));
      await renderAdminMenu();

      typeOption(option);
      pressEnterKey();

      const banner = await screen.findByRole('alert');
      expect(banner.textContent).toBe(INVALID_OPTION_MESSAGE);
      expect(navigateMock).not.toHaveBeenCalled();
    },
  );

  it('renders the line-24 ENTER=Continue and F3=Exit legend', async () => {
    await renderAdminMenu();

    expect(screen.getByRole('toolbar', { name: 'Function keys' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ENTER_KEY_LABEL })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: EXIT_KEY_LABEL })).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it('revokes the session and returns to the sign-on screen when F3=Exit is pressed', async () => {
    await renderAdminMenu();

    fireEvent.click(screen.getByRole('button', { name: EXIT_KEY_LABEL }));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledTimes(1);
    });
    expect(navigateMock).toHaveBeenCalledWith(SIGNON_ROUTE);
  });

  it('returns to the sign-on screen on the physical F3 key', async () => {
    await renderAdminMenu();

    fireEvent.keyDown(document, { key: 'F3' });

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledTimes(1);
    });
    expect(navigateMock).toHaveBeenCalledWith(SIGNON_ROUTE);
  });
});
