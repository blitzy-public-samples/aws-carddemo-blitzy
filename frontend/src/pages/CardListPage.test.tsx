/**
 * CardListPage tests
 * ==================
 *
 * :purpose: Verify the card list workflow of :func:`CardListPage` — the BMS
 *     mapset ``app/bms/COCRDLI.bms`` and its symbolic map
 *     ``app/cpy-bms/COCRDLI.CPY`` (CICS transaction ``CCLI``, program
 *     ``app/cbl/COCRDLIC.cbl``): the seven-rows-per-page browse, ``F7`` /
 *     ``F8`` paging with their end-of-list guards, the ``ACCTSID`` / ``CARDSID``
 *     filter widths and verbatim edit messages, the one-character row action
 *     column, and the ``F3`` / ``F7`` / ``F8`` legend.
 * :output: Assertions only; the suite writes no files and performs no I/O.
 * :note: ``../api`` is mocked with ``jest.unstable_mockModule`` (Jest runs this
 *     project as real ESM), so neither axios, the network, nor the Vite
 *     ``import.meta`` environment is evaluated; ``ApiError`` is passed through
 *     the mock, keeping the ``instanceof`` narrowing ``useApi`` performs on a
 *     failed call.
 * :note: The page is rendered inside ``Layout`` — the shell that renders the
 *     line-23 message and the line-24 function keys the page publishes through
 *     ``useScreenChrome`` — and inside ``MemoryRouter``, whose location it
 *     changes on row selection and on ``F3``.
 */
import { act, fireEvent, render, screen, within } from '@testing-library/react';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { RenderResult } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { ReactElement } from 'react';
import type {
  ApiErrorResponse,
  CardListItemDto,
  CardListRequestDto,
  CardListResponseDto,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';

/**
 * :purpose: Stand-in for the normalized ``../api`` error contract, declared with
 *     the same constructor signature and members as the real class so ``useApi``
 *     narrows a rejection with ``instanceof`` and rebuilds a foreign failure with
 *     ``new ApiError(0, message)`` exactly as it does in production.
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: resolved, human-readable error message.
 * :param body: standardized backend error body, when the response carried one.
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

/**
 * Mock of the ``../api`` card browse the page drives. Declared at module scope so
 * the ``unstable_mockModule`` factory closes over it and every test reconfigures
 * the one function instance the page is bound to.
 */
const listCardsMock =
  jest.fn<(request: CardListRequestDto) => Promise<CardListResponseDto>>();

/**
 * Mock of the ``../api`` sign-on call. The session store reached through the
 * shell binds to it; these tests seed the session directly and never call it.
 */
const signonMock =
  jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

jest.unstable_mockModule('../api', () => ({
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  listCards: listCardsMock,
  signon: signonMock,
  ApiError,
}));

/** Rows the mocked browse returns: three pages of seven, the last one partial. */
const MOCKED_CARD_COUNT = 15;

/** Leading digits that keep every generated ``CARD-NUM`` 16 characters wide. */
const CARD_NUMBER_PREFIX = '400000000000';

/** Leading digits that keep every generated ``CARD-ACCT-ID`` 11 digits wide. */
const ACCOUNT_ID_PREFIX = '000000000';

/** Account filter accepted by the ``2210-EDIT-ACCOUNT`` numeric edit. */
const VALID_ACCOUNT_FILTER = '00000000001';

/** Seeded user id and role (``CDEMO-USER-ID`` / ``CDEMO-USRTYP-USER``). */
const SESSION_USER = 'USER0001';

/** Router entry the card list screen is reached from. */
const CARD_LIST_ROUTE = '/cards';

/**
 * :purpose: Build the card number of one fixture row, keeping the 16-character
 *     ``CARD-NUM`` width and its ``string`` type (a 16-digit PAN exceeds
 *     ``Number.MAX_SAFE_INTEGER`` and may carry leading zeros).
 * :param sequence: one-based row number.
 * :returns: the 16-character card number.
 */
function cardNumberOf(sequence: number): string {
  return CARD_NUMBER_PREFIX + String(sequence).padStart(4, '0');
}

/**
 * :purpose: Emulate the server-side browse ``COCRDLIC`` performs. The screen never
 *     slices rows itself: it renders the page the server answered with, so the mock
 *     answers one page of seven and reports the page number it served together with
 *     whether a further page exists. ``PF8`` / ``PF7`` step that page number, and
 *     the source re-reads the page already displayed at either end of the file.
 * :param request: the browse request the screen issued.
 * :returns: the page the server answers with.
 */
function browse(request: CardListRequestDto): CardListResponseDto {
  const whole = cardListResponse(MOCKED_CARD_COUNT).cards;
  const rows = whole.filter(
    (row) =>
      (request.accountId === undefined || row.cardAcctId === request.accountId) &&
      (request.cardNum === undefined || row.cardNum >= request.cardNum),
  );
  const lastPage = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const requested = request.page ?? 1;
  const page = Math.min(Math.max(requested, 1), lastPage);
  const start = (page - 1) * PAGE_SIZE;
  return {
    ...cardListResponse(0),
    cards: rows.slice(start, start + PAGE_SIZE),
    pageNumber: page,
    nextPage: page < lastPage,
  };
}

/** Rows one page of the browse carries (``COCRDLI`` seven row lines). */
const PAGE_SIZE = 7;

/**
 * :purpose: Build the whole browse result, in ascending card-number order as the
 *     ``CXACAIX`` browse returned it.
 * :param count: number of rows to generate.
 * :returns: the response carrying ``count`` card rows on one page.
 */
function cardListResponse(count: number): CardListResponseDto {
  const cards: CardListItemDto[] = Array.from({ length: count }, (_, index) => {
    const sequence = index + 1;
    return {
      cardNum: cardNumberOf(sequence),
      cardAcctId: ACCOUNT_ID_PREFIX + String(sequence).padStart(2, '0'),
      cardActiveStatus: sequence % 2 === 0 ? 'N' : 'Y',
    };
  });
  return {
    cards,
    // The browse always answers with the one-based page it served (PAGENO).
    pageNumber: 1,
    nextPage: false,
    selectedCardNumber: null,
    selectedAction: null,
    message: null,
    infoMessage: null,
  };
}

type CardListPageComponent = (typeof import('./CardListPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SetSessionSeam = (typeof import('../hooks/useSession'))['__setSession'];

let CardListPage: CardListPageComponent;
let Layout: LayoutComponent;
let __setSession: SetSessionSeam;

beforeAll(async () => {
  // Imported after the mock is registered so the page, the shell, and the hooks
  // bind to the mocked ``../api``; no module reset, so they share the single
  // React instance already loaded by Testing Library.
  ({ default: CardListPage } = await import('./CardListPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ __setSession } = await import('../hooks/useSession'));
});

beforeEach(() => {
  listCardsMock.mockReset();
  listCardsMock.mockImplementation((request) => Promise.resolve(browse(request)));
  signonMock.mockReset();
  act(() => {
    __setSession(SESSION_USER, 'U');
  });
});

afterEach(() => {
  // Return the shared session store to signed out so no state leaks across tests.
  act(() => {
    __setSession(null, null);
  });
});

/**
 * :purpose: Publish the router location so a navigation the page performs (row
 *     selection, ``F3``) becomes observable in the DOM.
 * :returns: the current pathname.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <p data-testid="location">{location.pathname}</p>;
}

/**
 * :purpose: Render the card list screen inside the shared shell and the memory
 *     router, flushing the browse the page issues on entry so the rows are
 *     present once this resolves.
 * :returns: the Testing Library render result.
 */
async function renderCardListScreen(): Promise<RenderResult> {
  let view!: RenderResult;
  await act(async () => {
    view = render(
      <MemoryRouter initialEntries={[CARD_LIST_ROUTE]}>
        <LocationProbe />
        <Layout>
          <CardListPage />
        </Layout>
      </MemoryRouter>,
    );
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
  return view;
}

/**
 * :purpose: Render the card list on a routed tree whose sibling routes stand in for
 *     ``COCRDSLC`` and ``COCRDUPC``, so a row transfer really leaves this screen —
 *     as an ``XCTL`` did — instead of leaving it mounted on the new location.
 * :returns: the Testing Library render result.
 */
async function renderRoutedCardListScreen(): Promise<RenderResult> {
  let view!: RenderResult;
  await act(async () => {
    view = render(
      <MemoryRouter initialEntries={[CARD_LIST_ROUTE]}>
        <LocationProbe />
        <Routes>
          <Route
            path={CARD_LIST_ROUTE}
            element={
              <Layout>
                <CardListPage />
              </Layout>
            }
          />
          <Route
            path="/cards/view"
            element={<p data-testid="card-detail-screen">COCRDSLC</p>}
          />
          <Route
            path="/cards/update"
            element={<p data-testid="card-update-screen">COCRDUPC</p>}
          />
        </Routes>
      </MemoryRouter>,
    );
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
  return view;
}

/**
 * :purpose: Activate the screen's ENTER button and flush any browse it triggers.
 * :returns: nothing once the resulting state updates are applied.
 */
async function pressEnter(): Promise<void> {
  await act(async () => {
    // COCRDLI's line-24 legend carries no ENTER text, so the AID is declared with
    // its legend darkened and is reached only through the physical key.
    fireEvent.keyDown(document, { key: 'Enter' });
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Activate one function key of the line-24 legend by its label.
 * :param label: the legend text, for example ``F8=Forward``.
 * :returns: nothing once the resulting state updates are applied.
 */
async function pressPfKey(label: string): Promise<void> {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: label }));
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Type a value into a filter or row-action field of the screen.
 * :param testId: the field's ``data-testid``.
 * :param value: the value to place in the field.
 * :returns: nothing once the controlled input has re-rendered.
 */
function typeInto(testId: string, value: string): void {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

/**
 * :purpose: Read the card numbers of the rows currently on screen, in row order.
 * :returns: one card number per rendered ``.dataTable`` body row.
 */
function renderedCardNumbers(): string[] {
  return screen
    .getAllByTestId('card-list-row')
    .map((row) => row.children[2].textContent ?? '');
}

describe('CardListPage — seven rows per page', () => {
  it('renders exactly 7 data rows on the first page of a 15-card result set', async () => {
    const { container } = await renderCardListScreen();

    expect(container.querySelectorAll('.dataTable tbody tr')).toHaveLength(7);
    expect(screen.getAllByTestId('card-list-row')).toHaveLength(7);
    expect(renderedCardNumbers()).toEqual([
      cardNumberOf(1),
      cardNumberOf(2),
      cardNumberOf(3),
      cardNumberOf(4),
      cardNumberOf(5),
      cardNumberOf(6),
      cardNumberOf(7),
    ]);
    expect(screen.queryByText(cardNumberOf(8))).not.toBeInTheDocument();
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 1');
  });

  it('browses the whole card file with no filters on entry', async () => {
    await renderCardListScreen();

    expect(listCardsMock).toHaveBeenCalledTimes(1);
    expect(listCardsMock).toHaveBeenCalledWith({
      accountId: undefined,
      cardNum: undefined,
      // The browse always names the page it wants; entry asks for the first.
      page: 1,
      aid: undefined,
    });
  });

  it('renders the account id and active status of every row', async () => {
    await renderCardListScreen();

    const firstRow = screen.getAllByTestId('card-list-row')[0];
    expect(firstRow.children[1]).toHaveTextContent(ACCOUNT_ID_PREFIX + '01');
    expect(firstRow.children[3]).toHaveTextContent('Y');
    expect(screen.getAllByTestId('card-list-row')[1].children[3]).toHaveTextContent(
      'N',
    );
  });
});

describe('CardListPage — F7 backward and F8 forward paging', () => {
  it('pages forward to rows 8-14 on F8 and back to rows 1-7 on F7', async () => {
    await renderCardListScreen();

    await pressPfKey('F8=Forward');
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 2');
    expect(screen.getAllByTestId('card-list-row')).toHaveLength(7);
    expect(renderedCardNumbers()).toEqual([
      cardNumberOf(8),
      cardNumberOf(9),
      cardNumberOf(10),
      cardNumberOf(11),
      cardNumberOf(12),
      cardNumberOf(13),
      cardNumberOf(14),
    ]);
    expect(screen.queryByText(cardNumberOf(7))).not.toBeInTheDocument();

    await pressPfKey('F7=Backward');
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 1');
    expect(renderedCardNumbers()).toEqual([
      cardNumberOf(1),
      cardNumberOf(2),
      cardNumberOf(3),
      cardNumberOf(4),
      cardNumberOf(5),
      cardNumberOf(6),
      cardNumberOf(7),
    ]);
  });

  it('keeps F7 and F8 live on every page, including both boundaries', async () => {
    await renderCardListScreen();

    // ``COCRDLIC`` reaches PROCESS-PF7-KEY on page one and PROCESS-PF8-KEY on the
    // last page, re-reading the page already displayed, so neither key is ever
    // withdrawn.
    expect(screen.getByRole('button', { name: 'F7=Backward' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'F8=Forward' })).toBeEnabled();

    await pressPfKey('F8=Forward');
    expect(screen.getByRole('button', { name: 'F7=Backward' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'F8=Forward' })).toBeEnabled();

    await pressPfKey('F8=Forward');
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 3');
    expect(screen.getAllByTestId('card-list-row')).toHaveLength(1);
    expect(renderedCardNumbers()).toEqual([cardNumberOf(15)]);
    expect(screen.getByRole('button', { name: 'F8=Forward' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'F7=Backward' })).toBeEnabled();

    await pressPfKey('F8=Forward');
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 3');
    expect(renderedCardNumbers()).toEqual([cardNumberOf(15)]);
  });

  it('holds the first page when F7 is activated at the top of the browse', async () => {
    await renderCardListScreen();

    await pressPfKey('F7=Backward');
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 1');
    expect(renderedCardNumbers()[0]).toBe(cardNumberOf(1));
  });

  it('pages on the physical F8 and F7 attention keys', async () => {
    await renderCardListScreen();

    await act(async () => {
      fireEvent.keyDown(document, { key: 'F8' });
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 2');
    expect(renderedCardNumbers()[0]).toBe(cardNumberOf(8));

    await act(async () => {
      fireEvent.keyDown(document, { key: 'F7' });
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 1');
    expect(renderedCardNumbers()[0]).toBe(cardNumberOf(1));
  });

  it('re-browses from the first page when ENTER is pressed on a later page', async () => {
    await renderCardListScreen();

    await pressPfKey('F8=Forward');
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 2');

    await pressEnter();
    // Entry, the F8 browse, then the re-browse ENTER drives from the first page.
    expect(listCardsMock).toHaveBeenCalledTimes(3);
    expect(screen.getByTestId('page-number')).toHaveTextContent('Page 1');
    expect(renderedCardNumbers()[0]).toBe(cardNumberOf(1));
  });
});

describe('CardListPage — ACCTSID and CARDSID browse filters', () => {
  it('renders both filters at their legacy field widths', async () => {
    await renderCardListScreen();

    const accountFilter = screen.getByTestId('acctsid');
    const cardFilter = screen.getByTestId('cardsid');
    expect(accountFilter).toHaveAttribute('maxlength', '11');
    expect(cardFilter).toHaveAttribute('maxlength', '16');
    expect(accountFilter).toHaveValue('');
    expect(cardFilter).toHaveValue('');
  });

  it('rejects a non-numeric account filter with the verbatim COCRDLIC message', async () => {
    await renderCardListScreen();

    typeInto('acctsid', '1234567890X');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(
      'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER',
    );
    expect(listCardsMock).toHaveBeenCalledTimes(1);
  });

  it('rejects an account filter narrower than the 11 character field', async () => {
    await renderCardListScreen();

    typeInto('acctsid', '1234');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(
      'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER',
    );
    expect(listCardsMock).toHaveBeenCalledTimes(1);
  });

  it('rejects a non-numeric card filter with the verbatim COCRDLIC message', async () => {
    await renderCardListScreen();

    typeInto('acctsid', VALID_ACCOUNT_FILTER);
    typeInto('cardsid', '444433332222111X');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(
      'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER',
    );
    expect(listCardsMock).toHaveBeenCalledTimes(1);
  });

  it('browses on both filters once they are an 11 and a 16 digit number', async () => {
    await renderCardListScreen();

    typeInto('acctsid', VALID_ACCOUNT_FILTER);
    typeInto('cardsid', cardNumberOf(1));
    await pressEnter();

    expect(listCardsMock).toHaveBeenCalledTimes(2);
    expect(listCardsMock).toHaveBeenLastCalledWith({
      accountId: VALID_ACCOUNT_FILTER,
      cardNum: cardNumberOf(1),
      page: 1,
      aid: undefined,
    });
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
  });

  it('reports the verbatim no-records message for an empty result set', async () => {
    listCardsMock.mockResolvedValue({
      ...cardListResponse(0),
      // ``COCRDLIC`` moves its own no-records literal into WS-ERROR-MSG, so the
      // text is the service's and the screen only renders it.
      message: 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.',
    });

    await renderCardListScreen();

    expect(screen.getByRole('alert')).toHaveTextContent(
      'NO RECORDS FOUND FOR THIS SEARCH CONDITION.',
    );
    expect(screen.queryAllByTestId('card-list-row')).toHaveLength(0);
  });
});

describe('CardListPage — row action column', () => {
  it("opens the card detail screen for the row marked 'S'", async () => {
    await renderRoutedCardListScreen();
    const cardNum = cardNumberOf(3);
    expect(cardNum).toHaveLength(16);

    typeInto('card-select-3', 'S');
    await pressEnter();

    // The composite selection travels in the router location state, so the card
    // number never reaches the address bar or the session history.
    expect(screen.getByTestId('location').textContent).toBe('/cards/view');
    expect(screen.getByTestId('card-detail-screen')).toBeInTheDocument();
    expect(listCardsMock).toHaveBeenCalledTimes(1);
  });

  it('renders one single-character action field per displayed row', async () => {
    await renderCardListScreen();

    const actionField = screen.getByTestId('card-select-1');
    expect(actionField).toHaveAttribute('maxlength', '1');
    // Seven CRDSELn fields, one per displayed row; the eighth row is not on the page.
    expect(screen.getByTestId('card-select-7')).toBeInTheDocument();
    expect(screen.queryByTestId('card-select-8')).not.toBeInTheDocument();
  });

  it('reports the verbatim multi-selection message when two rows are marked', async () => {
    await renderCardListScreen();

    typeInto('card-select-1', 'S');
    typeInto('card-select-2', 'S');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(
      'PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE',
    );
    expect(screen.getByTestId('location').textContent).toBe(CARD_LIST_ROUTE);
  });

  it('reports the verbatim invalid action message for an unsupported code', async () => {
    await renderCardListScreen();

    typeInto('card-select-1', 'X');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent('INVALID ACTION CODE');
    expect(screen.getByTestId('location').textContent).toBe(CARD_LIST_ROUTE);
    expect(listCardsMock).toHaveBeenCalledTimes(1);
  });
});

describe('CardListPage — screen chrome and function keys', () => {
  it('publishes the CCLI / COCRDLIC chrome and the F3/F7/F8 legend', async () => {
    const { container } = await renderCardListScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CCLI');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COCRDLIC');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    expect(container.querySelector('.screen')).toHaveAttribute(
      'data-authenticated',
      'true',
    );

    const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });
    const legend = within(toolbar)
      .getAllByRole('button')
      .map((key) => key.textContent);
    expect(legend).toEqual(['F3=Exit', 'F7=Backward', 'F8=Forward']);
  });

  it('exits to the main menu on F3', async () => {
    await renderCardListScreen();

    await pressPfKey('F3=Exit');

    expect(screen.getByTestId('location').textContent).toBe('/menu');
  });

  it('surfaces a failed browse on the line-23 message region', async () => {
    listCardsMock.mockRejectedValue(new ApiError(500, 'Card browse unavailable'));

    await renderCardListScreen();

    expect(screen.getByRole('alert')).toHaveTextContent('Card browse unavailable');
    expect(screen.queryAllByTestId('card-list-row')).toHaveLength(0);
  });
});
