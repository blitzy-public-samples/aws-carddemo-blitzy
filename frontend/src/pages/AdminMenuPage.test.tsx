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
 * :note: Only ``../api`` is replaced, through ``jest.unstable_mockModule``:
 *     ``getAdminMenu`` is served from a fixture and the real ``ApiError`` class is
 *     passed through. ``react-router`` is the real module, so every transfer is
 *     asserted on the resolved router location rather than on a ``useNavigate`` spy,
 *     and the page is mounted inside the real :func:`Layout`, which renders the
 *     line-23 message region and the line-24 legend from the chrome the page
 *     publishes. Rationale is recorded in ``docs/decision-log.md``.
 */
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, useLocation } from 'react-router';
import type { ReactElement } from 'react';
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

/**
 * Level the row-4 screen heading renders at. The shared header owns ``h1`` (title01) and
 * ``h2`` (title02), so every screen body heading is an ``h3`` beneath them; the header
 * title02 also reads ``Admin Menu``.
 */
const SCREEN_HEADING_LEVEL = 3;

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
    },
    {
      optionNumber: 2,
      optionName: 'User Add (Security)                ',
      programName: 'COUSR01C',
    },
    {
      optionNumber: 3,
      optionName: 'User Update (Security)             ',
      programName: 'COUSR02C',
    },
    {
      optionNumber: 4,
      optionName: 'User Delete (Security)             ',
      programName: 'COUSR03C',
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
  return { dispatched: true, programName, message: null };
}

/**
 * :purpose: Build the gateway outcome for an option ``PROCESS-ENTER-KEY`` refused.
 * :param message: the verbatim line-23 message the program moves.
 * :returns: the refusing ``MenuSelectionResponseDto``.
 */
function refused(message: string): MenuSelectionResponseDto {
  return { dispatched: false, programName: null, message };
}

// The mocked barrel exposes the real ``ApiError`` class ``useApi`` narrows
// against, the fixture-backed ``getAdminMenu``, and the ``signon`` binding
// ``useSession`` imports.
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
  selectAdminMenuOption: selectAdminMenuOptionMock,
  __esModule: true,
  ApiError,
  getAdminMenu: getAdminMenuMock,
  signon: jest.fn(),
}));

type AdminMenuPageComponent = (typeof import('./AdminMenuPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let AdminMenuPage: AdminMenuPageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

/**
 * :purpose: Build the option row text the page renders for one served option
 *     (``CDEMO-ADMIN-OPT-NUM`` + ``'. '`` + ``CDEMO-ADMIN-OPT-NAME``), with the
 *     legacy padding collapsed the way the DOM text comparison normalizes it.
 *     ``COADM02Y`` declares the number ``PIC 9(02)`` and ``COADM01C`` L233-L236 strings
 *     it ``DELIMITED BY SIZE``, so the row carries two digits.
 * :param option: the served menu option.
 * :returns: the expected row text.
 */
function optionLabel(option: MenuOption): string {
  return `${String(option.optionNumber).padStart(2, '0')}. ${option.optionName.trim()}`;
}

/**
 * :purpose: Publish the router location so a transfer the screen performs becomes
 *     observable in the DOM, without replacing ``useNavigate``.
 * :returns: the span carrying the active pathname.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
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
      <LocationProbe />
    </MemoryRouter>,
  );
}

/**
 * :purpose: Read the resolved router pathname the screen last transferred to.
 * :returns: the active pathname.
 */
function currentPath(): string {
  return screen.getByTestId('location').textContent ?? '';
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
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getAdminMenuMock.mockReset();
  getAdminMenuMock.mockResolvedValue(ADMIN_MENU_RESPONSE);
  selectAdminMenuOptionMock.mockReset();
  await seedSignedOnSession(ADMIN_USER_ID, ADMIN_ROLE);
});

afterEach(async () => {
  await seedSignedOutSession();
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

    // ``OPTIONI`` is ``PIC X(2)``: it holds two positions of whatever was keyed, and
    // ``PROCESS-ENTER-KEY`` is what judges the content. Discarding the characters the
    // program's own class test refuses would rewrite the entry into a different, valid
    // option and dispatch that one.
    typeOption('1x34');
    expect(field).toHaveValue('1x');
    typeOption('AB');
    expect(field).toHaveValue('AB');
  });

  it('refuses a non-digit entry in place rather than dispatching a digit inside it', async () => {
    await renderAdminMenu();

    typeOption('-1');
    pressEnterKey();

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe(INVALID_OPTION_MESSAGE);
    // ``PROCESS-ENTER-KEY`` reaches its ``XCTL`` only past the edit, so nothing is sent
    // and no other program is entered.
    expect(selectAdminMenuOptionMock).not.toHaveBeenCalled();
    expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    expect(optionField()).toHaveValue('-1');
    // Every re-send of the map re-applies the mapset's ``IC`` attribute.
    expect(optionField()).toHaveFocus();
  });

  it('refuses an option past the four the admin table declares', async () => {
    await renderAdminMenu();

    typeOption('5');
    pressEnterKey();

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe(INVALID_OPTION_MESSAGE);
    expect(selectAdminMenuOptionMock).not.toHaveBeenCalled();
    expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
  });

  it.each(ADMIN_OPTION_ROUTES)(
    'routes admin option %i, dispatched to %s, to %s',
    async (option, programName, route) => {
      selectAdminMenuOptionMock.mockResolvedValue(dispatched(programName));
      await renderAdminMenu();

      typeOption(String(option));
      pressEnterKey();

      await waitFor(() => {
        expect(currentPath()).toBe(route);
      });
      expect(selectAdminMenuOptionMock).toHaveBeenCalledWith({
        option: String(option),
        aid: 'ENTER',
      });
    },
  );

  it('submits the entered option when ENTER is pressed in the OPTION field', async () => {
    selectAdminMenuOptionMock.mockResolvedValue(dispatched('COUSR00C'));
    await renderAdminMenu();

    typeOption('1');
    fireEvent.keyDown(optionField(), { key: 'Enter' });

    await waitFor(() => {
      expect(currentPath()).toBe('/users');
    });
  });

  it('leaves ENTER to the shell, so a repeat inside one task sends once', async () => {
    // COADM01 declares the same single ENTER affordance every other mapset does, and a
    // 3270 keyboard is locked from the moment an AID is sent until the program replies --
    // so a second ENTER struck in the meantime is DISCARDED. The shell owns that rule for
    // all 17 screens through its per-AID latch, which only applies to a key the shell
    // actually sees. A page-local handler that swallowed ENTER bypassed the latch (and the
    // per-send announcement and cursor rules with it), so this screen sent twice where
    // every other screen sent once.
    selectAdminMenuOptionMock.mockResolvedValue(dispatched('COUSR00C'));
    await renderAdminMenu();

    typeOption('1');
    fireEvent.keyDown(optionField(), { key: 'Enter' });
    fireEvent.keyDown(optionField(), { key: 'Enter' });

    await waitFor(() => {
      expect(currentPath()).toBe('/users');
    });
    expect(selectAdminMenuOptionMock).toHaveBeenCalledTimes(1);
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
      expect(currentPath()).toBe(ADMIN_MENU_ROUTE);
    },
  );

  it('renders the line-24 ENTER=Continue and F3=Exit legend', async () => {
    await renderAdminMenu();

    expect(screen.getByRole('group', { name: 'Function keys' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ENTER_KEY_LABEL })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: EXIT_KEY_LABEL })).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it('revokes the session and returns to the sign-on screen when F3=Exit is pressed', async () => {
    await renderAdminMenu();

    fireEvent.click(screen.getByRole('button', { name: EXIT_KEY_LABEL }));

    await waitFor(() => {
      expect(currentPath()).toBe(SIGNON_ROUTE);
    });
  });

  it('returns to the sign-on screen on the physical F3 key', async () => {
    await renderAdminMenu();

    fireEvent.keyDown(document, { key: 'F3' });

    await waitFor(() => {
      expect(currentPath()).toBe(SIGNON_ROUTE);
    });
  });
});
