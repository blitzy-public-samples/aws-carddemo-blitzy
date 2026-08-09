/**
 * :module: MainMenuPage.test
 * :purpose: Colocated suite for the authenticated main-menu screen — mapset
 *     ``COMEN01``, CICS transaction ``CM00``, program ``COMEN01C``. Covers the
 *     menu load and its option lines, the two-character ``OPTION`` entry field,
 *     navigation to the selected option's route, the verbatim invalid-option and
 *     administrator-only refusal messages, and the line-24 ``ENTER=Continue`` /
 *     ``F3=Exit`` handlers.
 * :note: ``../api`` is replaced with ``jest.unstable_mockModule``, so neither the
 *     axios client nor the Vite ``import.meta`` read behind it is ever evaluated;
 *     the screen, the shell and the hook barrel are therefore imported
 *     dynamically once the mock is registered. The screen renders its body only —
 *     it publishes the line-23 message and the line-24 legend into the shared
 *     shell — so every case renders it inside ``Layout``. Rationale for the
 *     non-literal choices is in ``docs/decision-log.md``.
 */
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE02 } from '../types';
import type { ReactElement } from 'react';
import { CDEMO_USRTYP_USER } from '../types';
import type {
  ApiErrorResponse,
  MenuOption,
  MenuResponseDto,
  MenuSelectionRequestDto,
  MenuSelectionResponseDto,
} from '../types';

/** Width of ``CDEMO-MENU-OPT-NAME`` (``PIC X(35)``), as the gateway transmits it. */
const OPTION_NAME_WIDTH = 35;

/** Length of the ``OPTION`` field (``OPTIONI PIC X(2)``, BMS ``LENGTH=2``). */
const OPTION_FIELD_LENGTH = 2;

/** Option slots the mapset provides (``OPTN001``..``OPTN012``). */
const MENU_OPTION_SLOTS = 12;

/** Signed-on user id seeded into the session store for every case. */
const USER_ID = 'USER01';

/** Route the screen is mounted on, so a navigation shows as a change. */
const MENU_ROUTE = '/menu';

/** Route PF3 returns to (legacy ``RETURN-TO-SIGNON-SCREEN``). */
const SIGNON_ROUTE = '/signon';

/** Invalid-option message of ``COMEN01C`` ``PROCESS-ENTER-KEY``, verbatim. */
const MSG_INVALID_OPTION = 'Please enter a valid option number...';

/**
 * Administrator-only refusal of ``COMEN01C`` ``PROCESS-ENTER-KEY``, verbatim;
 * the trailing blank belongs to the legacy literal.
 */
const MSG_NO_ACCESS = 'No access - Admin Only option... ';

/** HTTP status the gateway answers a refused menu request with. */
const REFUSED_STATUS = 400;

/**
 * :purpose: Stand-in for the ``ApiError`` the axios client raises, with the same
 *     construction signature so ``useApi`` recognizes it through ``instanceof``
 *     and surfaces its message unchanged.
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: resolved, human-readable error message.
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

/** Mocked ``GET /menu`` call; each case sets its own payload or failure. */
const getMainMenuMock = jest.fn<() => Promise<MenuResponseDto>>();

/** Mocked ``POST /auth/signon``; imported by ``useSession``, never exercised here. */
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
  selectMenuOption: selectMenuOptionMock,
  __esModule: true,
  ApiError,
  getMainMenu: getMainMenuMock,
  signon: signonMock,
}));

/**
 * :purpose: Build one wire-shaped menu option, padding the label to the copybook
 *     width the gateway sends.
 * :param optionNumber: one-based option number the operator types.
 * :param optionName: label as declared in ``COMEN02Y``.
 * :param programName: legacy target program.
 * :returns: the option exactly as ``GET /menu`` returns it.
 */
function menuOption(
  optionNumber: number,
  optionName: string,
  programName: string,
): MenuOption {
  return {
    optionNumber,
    optionName: optionName.padEnd(OPTION_NAME_WIDTH, ' '),
    programName,
  };
}

/**
 * Mocked ``POST /menu/select`` call. ``COMEN01C`` ``PROCESS-ENTER-KEY`` runs on the
 * gateway, so the option edits and the dispatched program come from the server and
 * the screen only resolves that program to its own route.
 */
const selectMenuOptionMock =
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

/** The ten ``CDEMO-MENU-OPTIONS`` rows the gateway returns for a standard user. */
const MAIN_MENU_OPTIONS: MenuOption[] = [
  menuOption(1, 'Account View', 'COACTVWC'),
  menuOption(2, 'Account Update', 'COACTUPC'),
  menuOption(3, 'Credit Card List', 'COCRDLIC'),
  menuOption(4, 'Credit Card View', 'COCRDSLC'),
  menuOption(5, 'Credit Card Update', 'COCRDUPC'),
  menuOption(6, 'Transaction List', 'COTRN00C'),
  menuOption(7, 'Transaction View', 'COTRN01C'),
  menuOption(8, 'Transaction Add', 'COTRN02C'),
  menuOption(9, 'Transaction Reports', 'CORPT00C'),
  menuOption(10, 'Bill Payment', 'COBIL00C'),
];

/** The option lines rendered for :data:`MAIN_MENU_OPTIONS`, in server order. */
const MAIN_MENU_LINES = [
  '01. Account View',
  '02. Account Update',
  '03. Credit Card List',
  '04. Credit Card View',
  '05. Credit Card Update',
  '06. Transaction List',
  '07. Transaction View',
  '08. Transaction Add',
  '09. Transaction Reports',
  '10. Bill Payment',
];

/**
 * :purpose: Wrap options in a main-menu response.
 * :param options: the options the gateway returns; defaults to all ten rows.
 * :returns: the ``MenuResponseDto`` payload for ``CM00`` / ``COMEN01C``.
 */
function menuResponse(options: MenuOption[] = MAIN_MENU_OPTIONS): MenuResponseDto {
  return { tranId: 'CM00', programName: 'COMEN01C', options, message: null };
}

type MainMenuPageComponent = (typeof import('./MainMenuPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let MainMenuPage: MainMenuPageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the screen, the shell and the hook
  // barrel bind to the mocked ``../api``; no module reset, so they share the
  // React instance Testing Library already loaded.
  ({ default: MainMenuPage } = await import('./MainMenuPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getMainMenuMock.mockReset();
  selectMenuOptionMock.mockReset();
  getMainMenuMock.mockResolvedValue(menuResponse());
  await seedSignedOnSession(USER_ID, CDEMO_USRTYP_USER);
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * :purpose: Report the router location so a navigation the screen raises is
 *     observable in the DOM.
 * :returns: the current pathname.
 */
function LocationProbe(): ReactElement {
  const { pathname } = useLocation();
  return <span data-testid="location">{pathname}</span>;
}

/**
 * :purpose: Render the screen inside the shared 24x80 shell and a memory router,
 *     so the line-23 message region and the line-24 legend it publishes render
 *     as they do in the application.
 * :returns: the Testing Library render result.
 */
function renderMainMenu(): ReturnType<typeof render> {
  return render(
    <MemoryRouter initialEntries={[MENU_ROUTE]}>
      <Layout>
        <MainMenuPage />
      </Layout>
      <LocationProbe />
    </MemoryRouter>,
  );
}

/**
 * :purpose: Render the screen and wait for the option lines the mocked
 *     ``getMainMenu`` resolves.
 * :param optionCount: number of option lines expected once loaded.
 * :returns: the Testing Library render result.
 */
async function renderLoadedMainMenu(
  optionCount: number = MAIN_MENU_OPTIONS.length,
): Promise<ReturnType<typeof render>> {
  const rendered = renderMainMenu();
  await waitFor(() => {
    expect(screenBody().getAllByRole('listitem')).toHaveLength(optionCount);
  });
  return rendered;
}

/**
 * :purpose: Scope queries to the screen body (rows 4-22); the shell header
 *     renders its own ``Main Menu`` title heading outside it.
 * :returns: queries bound to the body region.
 */
function screenBody() {
  return within(screen.getByRole('main'));
}

/**
 * :returns: the ``OPTION`` entry field, named by the row-20 prompt of the mapset.
 */
function optionField(): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>('Please select an option :');
}

/** :returns: the line-23 message text, or ``null`` when the region is empty. */
function messageText(): string | null {
  const message = screen.queryByRole('alert');
  return message === null ? null : message.textContent;
}

/** :returns: the current router pathname. */
function currentPath(): string {
  return screen.getByTestId('location').textContent ?? '';
}

/**
 * :purpose: Activate the line-24 ENTER key through its legend button.
 * :returns: a promise resolving once the click has been dispatched.
 */
async function pressEnterKey(): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: 'ENTER=Continue' }));
}

describe('MainMenuPage — menu load (COMEN01 / CM00)', () => {
  it('renders the Main Menu heading and the option lines the gateway returns', async () => {
    await renderLoadedMainMenu();

    expect(screenBody().getByRole('heading', { name: 'Main Menu' })).toBeInTheDocument();
    expect(getMainMenuMock).toHaveBeenCalledTimes(1);

    const lines = screenBody()
      .getAllByRole('listitem')
      .map((line) => line.textContent?.trim());
    expect(lines).toEqual(MAIN_MENU_LINES);
    await waitFor(() => {
      expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'false');
    });
  });

  it('renders whichever role-filtered option set the gateway returns', async () => {
    getMainMenuMock.mockResolvedValue(
      menuResponse([
        menuOption(6, 'Transaction List', 'COTRN00C'),
        menuOption(9, 'Transaction Reports', 'CORPT00C'),
      ]),
    );

    await renderLoadedMainMenu(2);

    expect(screenBody().getByText('06. Transaction List')).toBeInTheDocument();
    expect(screenBody().getByText('09. Transaction Reports')).toBeInTheDocument();
    expect(screenBody().queryByText('01. Account View')).not.toBeInTheDocument();
  });

  it('renders at most the twelve option slots the mapset provides', async () => {
    getMainMenuMock.mockResolvedValue(
      menuResponse(
        Array.from({ length: MENU_OPTION_SLOTS + 2 }, (_, index) =>
          menuOption(index + 1, `Option ${String(index + 1)}`, 'COACTVWC'),
        ),
      ),
    );

    await renderLoadedMainMenu(MENU_OPTION_SLOTS);

    expect(screenBody().getByText('12. Option 12')).toBeInTheDocument();
    expect(screenBody().queryByText('13. Option 13')).not.toBeInTheDocument();
  });

  it('publishes the CM00 transaction identity and the Main Menu title into the shell', async () => {
    await renderLoadedMainMenu();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CM00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COMEN01C');
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    expect(messageText()).toBeNull();
  });
});

describe('MainMenuPage — OPTION entry field', () => {
  it('is a two-character numeric field', async () => {
    await renderLoadedMainMenu();

    const field = optionField();
    expect(field).toHaveAttribute('maxlength', String(OPTION_FIELD_LENGTH));
    expect(field).toHaveAttribute('inputmode', 'numeric');
    expect(field).toHaveValue('');
  });

  it('keeps the entry as it was made and stops at two characters', async () => {
    await renderLoadedMainMenu();

    // ``OPTIONI`` is ``PIC X(2)``: it holds two positions of whatever was keyed, and
    // ``PROCESS-ENTER-KEY`` is what judges the content. Discarding the characters the
    // program's own class test refuses would rewrite the entry into a different, valid
    // option — which is how ``-1`` came to open Account View.
    const field = optionField();
    await userEvent.type(field, 'a1b2');
    expect(field).toHaveValue('a1');

    await userEvent.type(field, '3');
    expect(field).toHaveValue('a1');
  });

  it('refuses a signed entry in place rather than dispatching the digit inside it', async () => {
    await renderLoadedMainMenu();

    await userEvent.type(optionField(), '-1');
    expect(optionField()).toHaveValue('-1');
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_INVALID_OPTION);
    });
    // ``PROCESS-ENTER-KEY`` reaches its ``XCTL`` only past the edit, so nothing is sent
    // and no other program is entered.
    expect(selectMenuOptionMock).not.toHaveBeenCalled();
    expect(currentPath()).toBe(MENU_ROUTE);
    expect(optionField()).toHaveValue('-1');
  });

  it('returns the cursor to the OPTION field on every refusing pass', async () => {
    await renderLoadedMainMenu();

    const field = optionField();
    await userEvent.type(field, 'x');
    await userEvent.click(screen.getByRole('button', { name: 'ENTER=Continue' }));

    await waitFor(() => {
      expect(messageText()).toBe(MSG_INVALID_OPTION);
    });
    // Every re-send of the map re-applies the mapset's ``IC`` attribute, so a second
    // identical refusal places the cursor a second time even though line 23 is unchanged.
    expect(field).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'ENTER=Continue' }));

    expect(messageText()).toBe(MSG_INVALID_OPTION);
    expect(field).toHaveFocus();
    expect(selectMenuOptionMock).not.toHaveBeenCalled();
  });
});

describe('MainMenuPage — option selection', () => {
  it.each([
    { option: '1', program: 'COACTVWC', route: '/accounts' },
    { option: '2', program: 'COACTUPC', route: '/accounts/update' },
    { option: '3', program: 'COCRDLIC', route: '/cards' },
    { option: '4', program: 'COCRDSLC', route: '/cards/view' },
    { option: '5', program: 'COCRDUPC', route: '/cards/update' },
    { option: '6', program: 'COTRN00C', route: '/transactions' },
    { option: '7', program: 'COTRN01C', route: '/transactions/view' },
    { option: '8', program: 'COTRN02C', route: '/transactions/add' },
    { option: '9', program: 'CORPT00C', route: '/reports' },
    { option: '10', program: 'COBIL00C', route: '/billpay' },
  ])(
    'navigates to $route when option $option is entered',
    async ({ option, program, route }) => {
      selectMenuOptionMock.mockResolvedValue(dispatched(program));
      await renderLoadedMainMenu();

      await userEvent.type(optionField(), option);
      await pressEnterKey();

      expect(selectMenuOptionMock).toHaveBeenCalledWith({ option, aid: 'ENTER' });
      await waitFor(() => {
        expect(currentPath()).toBe(route);
      });
      expect(messageText()).toBeNull();
    },
  );

  /*
   * COMEN01C runs its option edit before it dispatches anything, so each of the three
   * refusals below is answered without a request. The server is left mocked to refuse as
   * well, so the assertion that nothing was sent is the whole point: were the edit missing,
   * the mock would supply the same message and hide it.
   */
  it.each([
    { name: 'a blank option', entry: '' },
    { name: 'option zero, as the legacy zero check does', entry: '0' },
    { name: 'an option number beyond the declared option count', entry: '99' },
    { name: 'a signed entry, which is NOT NUMERIC in a PIC 9(02) field', entry: '-1' },
    { name: 'a fractional entry, which is NOT NUMERIC too', entry: '1.' },
  ])('refuses $name with the verbatim invalid-option message', async ({ entry }) => {
    selectMenuOptionMock.mockResolvedValue(refused(MSG_INVALID_OPTION));
    await renderLoadedMainMenu();

    if (entry !== '') {
      await userEvent.type(optionField(), entry);
    }
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_INVALID_OPTION);
    });
    expect(selectMenuOptionMock).not.toHaveBeenCalled();
    expect(currentPath()).toBe(MENU_ROUTE);
  });

  it('marks the refused OPTION field invalid and leaves the cursor on it', async () => {
    await renderLoadedMainMenu();

    await userEvent.type(optionField(), '-1');
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_INVALID_OPTION);
    });
    expect(optionField()).toHaveAttribute('aria-invalid', 'true');
    expect(optionField()).toHaveFocus();
  });

  it('keeps a signed entry in the field so the edit can judge it', async () => {
    await renderLoadedMainMenu();

    // A 3270 numeric field admits the minus sign, which is why COMEN01C tests
    // IS NOT NUMERIC. Dropping it here would silently turn -1 into option 1.
    await userEvent.type(optionField(), '-1');

    expect(optionField()).toHaveValue('-1');
  });
});

describe('MainMenuPage — administrator-only gating', () => {
  it('shows the verbatim admin-only refusal when the role gate rejects the request', async () => {
    getMainMenuMock.mockRejectedValue(new ApiError(REFUSED_STATUS, MSG_NO_ACCESS));

    renderMainMenu();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_NO_ACCESS);
    });
    expect(screenBody().queryAllByRole('listitem')).toHaveLength(0);
    expect(currentPath()).toBe(MENU_ROUTE);
  });

  it('refuses an option the gateway withheld from a standard user', async () => {
    getMainMenuMock.mockResolvedValue(
      menuResponse([menuOption(1, 'Account View', 'COACTVWC')]),
    );

    selectMenuOptionMock.mockResolvedValue(refused(MSG_INVALID_OPTION));
    await renderLoadedMainMenu(1);

    await userEvent.type(optionField(), '2');
    await pressEnterKey();

    await waitFor(() => {
      expect(messageText()).toBe(MSG_INVALID_OPTION);
    });
    expect(currentPath()).toBe(MENU_ROUTE);
  });

  it('never refuses a standard-user option for a signed-on standard user', async () => {
    selectMenuOptionMock.mockResolvedValue(dispatched('COTRN02C'));
    await renderLoadedMainMenu();

    await userEvent.type(optionField(), '8');
    await pressEnterKey();

    expect(messageText()).not.toBe(MSG_NO_ACCESS);
    await waitFor(() => {
      expect(currentPath()).toBe('/transactions/add');
    });
  });
});

describe('MainMenuPage — PF keys', () => {
  it('publishes exactly the two legend keys the mapset declares', async () => {
    await renderLoadedMainMenu();

    const legend = within(screen.getByRole('group', { name: 'Function keys' }));
    expect(legend.getByRole('button', { name: 'ENTER=Continue' })).toBeInTheDocument();
    expect(legend.getByRole('button', { name: 'F3=Exit' })).toBeInTheDocument();
    expect(legend.getAllByRole('button')).toHaveLength(2);
  });

  it('submits the entered option when the ENTER key is pressed', async () => {
    selectMenuOptionMock.mockResolvedValue(dispatched('COTRN00C'));
    await renderLoadedMainMenu();

    await userEvent.type(optionField(), '6');
    fireEvent.keyDown(document, { key: 'Enter' });

    await waitFor(() => {
      expect(currentPath()).toBe('/transactions');
    });
    expect(messageText()).toBeNull();
  });

  it('submits the entered option when ENTER is typed in the OPTION field', async () => {
    selectMenuOptionMock.mockResolvedValue(dispatched('CORPT00C'));
    await renderLoadedMainMenu();

    await userEvent.type(optionField(), '9{Enter}');

    await waitFor(() => {
      expect(currentPath()).toBe('/reports');
    });
    expect(messageText()).toBeNull();
  });

  it('exits to the sign-on screen and clears the session on the F3 legend key', async () => {
    const { container } = await renderLoadedMainMenu();

    await userEvent.click(screen.getByRole('button', { name: 'F3=Exit' }));

    // The exit revokes the SERVER session before it navigates, so the transfer completes
    // on a later tick than the click; the sibling physical-key test waits the same way.
    await waitFor(() => {
      expect(currentPath()).toBe(SIGNON_ROUTE);
    });
    expect(container.querySelector('.screen')).toHaveAttribute('data-authenticated', 'false');
  });

  it('exits to the sign-on screen when the F3 key is pressed', async () => {
    await renderLoadedMainMenu();

    fireEvent.keyDown(document, { key: 'F3' });

    await waitFor(() => {
      expect(currentPath()).toBe(SIGNON_ROUTE);
    });
  });
});

