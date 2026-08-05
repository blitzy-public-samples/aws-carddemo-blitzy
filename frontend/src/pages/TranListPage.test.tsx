/**
 * TranListPage.test
 * =================
 *
 * :purpose: Verify the transaction-list workflow of :func:`TranListPage` — the
 *     React replacement for BMS mapset ``app/bms/COTRN00.bms`` (CICS transaction
 *     ``CT00``, program ``COTRN00C``): the ten-rows-per-page browse declared by
 *     the mapset's ``SEL0001``..``SEL0010`` row groups, PF7/PF8 paging and its
 *     boundary states, the 16-character ``Search Tran ID:`` (``TRNIDIN``) filter
 *     and its numeric class test, the ``S``-only row-selection flag, the
 *     drill-through to the transaction-view screen, and the line-23 message and
 *     line-24 function-key regions.
 * :output: A Jest suite; every message, caption and route asserted here is the
 *     verbatim legacy literal (``app/cbl/COTRN00C.cbl``) or its mapset caption
 *     (``app/cpy-bms/COTRN00.CPY``).
 * :note: ``../api`` is replaced with ``jest.unstable_mockModule`` so no axios
 *     instance, no network and no Vite ``import.meta`` is ever evaluated; the
 *     screen, the shell and the hooks are imported dynamically once the mock is
 *     registered, sharing one React instance with the statically imported
 *     Testing Library.
 */

// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { render, screen, act, fireEvent, within } from '@testing-library/react';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { RenderResult } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { ReactElement } from 'react';
import type {
  SignonRequestDto,
  SignonResponseDto,
  TranListItemDto,
  TranListRequestDto,
  TranListResponseDto,
} from '../types';

/**
 * Stable mock of the ``../api`` ``listTransactions`` named export, reset before
 * each test. Declared at module scope so the ``unstable_mockModule`` factory can
 * close over it.
 */
const listTransactionsMock =
  jest.fn<(request: TranListRequestDto) => Promise<TranListResponseDto>>();

/**
 * Mock of the ``../api`` ``signon`` named export. ``useSession`` — reached through
 * the ``../hooks`` barrel and the screen shell — imports it statically, so the
 * mocked module must provide it for the ES-module link to resolve.
 */
const signonMock = jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>();

/**
 * :purpose: The normalized REST error contract of ``../api``, reproduced for the
 *     mocked module so ``useApi`` keeps its ``instanceof`` check and its
 *     ``new ApiError(0, message)`` fallback without loading the real
 *     ``../api/client`` (which reads ``import.meta`` through ``./config``).
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: already-resolved, human-readable error message.
 * :param isOptimisticLockConflict: ``true`` only for the HTTP ``409`` conflict.
 */
class ApiError extends Error {
  readonly status: number;

  readonly isOptimisticLockConflict: boolean;

  constructor(status: number, message: string, isOptimisticLockConflict = false) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
  }
}

jest.unstable_mockModule('../api', () => ({
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  listTransactions: listTransactionsMock,
  signon: signonMock,
  ApiError,
}));

/** Rejection message for a row-selection flag other than ``S`` (``COTRN00C``). */
const MSG_INVALID_SELECTION = 'Invalid selection. Valid value is S';

/** Rejection message for a non-numeric browse filter (``COTRN00C``). */
const MSG_TRAN_ID_NUMERIC = 'Tran ID must be Numeric ...';

/** Empty-result text shown in place of the ten row lines. */
const EMPTY_ROW_TEXT = 'No transactions to display';

/** Top-of-browse informational message of ``COTRN00C``. */
const MSG_TOP_OF_PAGE = 'You are at the top of the page...';

/** Read-failure message of ``COTRN00C``. */
const MSG_LOOKUP_FAILED = 'Unable to lookup transaction...';

/** Prompt of the ``TRNIDIN`` browse filter (mapset line 6). */
const FILTER_LABEL = 'Search Tran ID:';

/** Field name of the browse filter (BMS ``TRNIDIN``). */
const FILTER_FIELD_NAME = 'TRNIDIN';

/** ENTER entry of the mapset line-24 legend, carried by the submit control. */
const SUBMIT_LABEL = 'ENTER=Continue';

/** PF3 entry of the line-24 legend (BMS ``'ENTER=Continue  F3=Back  ...'``). */
const PF3_LABEL = 'F3=Back';

/** PF7 entry of the line-24 legend. */
const PF7_LABEL = 'F7=Backward';

/** PF8 entry of the line-24 legend. */
const PF8_LABEL = 'F8=Forward';

/** Static line-21 instruction literal of the mapset. */
const SELECTION_HINT = "Type 'S' to View Transaction details from the list";

/** Width of a transaction id and of the ``TRNIDIN`` filter (``PIC X(16)``). */
const TRAN_ID_WIDTH = 16;

/** Rows in the mocked result: more than one page, and not a whole multiple of it. */
const MOCKED_ROW_COUNT = 22;

/** Route of the transaction-list screen itself. */
const LIST_ROUTE = '/transactions';

/** Route prefix of the transaction-view screen (``CT01`` / ``COTRN01C``). */
const TRANSACTION_VIEW_ROUTE = '/transactions/';

/** Route of the main menu, reached with PF3 (legacy ``XCTL`` to ``COMEN01C``). */
const MENU_ROUTE = '/menu';

/** Authenticated user id seeded into the session store for every test. */
const TEST_USER = 'USER0001';

type TranListPageModule = typeof import('./TranListPage');
type LayoutModule = typeof import('../components/Layout');
type SessionStoreModule = typeof import('../hooks/useSession');

let TranListPage: TranListPageModule['default'];
let Layout: LayoutModule['default'];
let setSession: SessionStoreModule['__setSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the screen, the shell and the hooks
  // all bind to the mocked ``../api``; no module reset, so they share React with
  // Testing Library.
  ({ default: TranListPage } = await import('./TranListPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ __setSession: setSession } = await import('../hooks/useSession'));
});

/**
 * :purpose: Render a transaction id at its fixed ``PIC X(16)`` width.
 * :param rowNumber: one-based ordinal of the row in the mocked result.
 * :returns: the 16-character zero-padded transaction id.
 */
function tranIdOf(rowNumber: number): string {
  return String(rowNumber).padStart(TRAN_ID_WIDTH, '0');
}

/**
 * :purpose: Build the inclusive range of transaction ids the given rows carry.
 * :param first: one-based ordinal of the first row.
 * :param last: one-based ordinal of the last row.
 * :returns: the ordered transaction ids of the range.
 */
function tranIdRange(first: number, last: number): string[] {
  const ids: string[] = [];
  for (let rowNumber = first; rowNumber <= last; rowNumber += 1) {
    ids.push(tranIdOf(rowNumber));
  }
  return ids;
}

/**
 * :purpose: Build one list row in the wire shape the api-gateway returns: a
 *     16-character id, an ``MM/DD/YY`` short date, a description, and a signed
 *     scale-2 ``NUMERIC(11,2)`` amount string.
 * :param rowNumber: one-based ordinal of the row in the mocked result.
 * :returns: the populated list-row DTO.
 */
function listRow(rowNumber: number): TranListItemDto {
  const ordinal = String(rowNumber).padStart(2, '0');
  const sign = rowNumber % 2 === 0 ? '-' : '';
  return {
    tranId: tranIdOf(rowNumber),
    tranDate: `01/${ordinal}/22`,
    tranDesc: `PURCHASE ${String(rowNumber).padStart(4, '0')}`,
    tranAmt: `${sign}${rowNumber * 10}.${ordinal}`,
  };
}

/**
 * :purpose: Build a list response around the supplied rows, defaulting to the
 *     first forward page of a browse whose server side has no further page.
 * :param rows: the rows the response carries.
 * :param overrides: response members to replace.
 * :returns: the populated list response DTO.
 */
function listResponse(
  rows: TranListItemDto[],
  overrides: Partial<TranListResponseDto> = {},
): TranListResponseDto {
  return {
    transactions: rows,
    pageNumber: 1,
    tranIdFirst: rows.length === 0 ? null : rows[0].tranId,
    tranIdLast: rows.length === 0 ? null : rows[rows.length - 1].tranId,
    nextPage: false,
    selectedTranId: null,
    message: null,
    ...overrides,
  };
}

/** The 22 mocked rows: three server pages of ten, ten and two. */
const mockedRows: TranListItemDto[] = Array.from({ length: MOCKED_ROW_COUNT }, (_, index) =>
  listRow(index + 1),
);

/** Rows one page of the browse carries (``COTRN00`` ``TRAN-REC OCCURS 10``). */
const PAGE_SIZE = 10;

/**
 * :purpose: Emulate the server-side browse ``COTRN00C`` performs. The screen never
 *     slices rows itself: it renders the page the server answered with, so the mock
 *     answers one page of ten and reports the page number it served together with
 *     whether a further page exists. ``PF8`` / ``PF7`` step that page number, and
 *     ``PROCESS-PF8-KEY`` / ``PROCESS-PF7-KEY`` re-read the same page at either end
 *     of the file.
 * :param request: the browse request the screen issued.
 * :returns: the page the server answers with.
 */
function browse(request: TranListRequestDto): TranListResponseDto {
  const filter = request.tranIdFilter ?? '';
  const rows =
    filter === '' ? mockedRows : mockedRows.filter((row) => row.tranId >= filter);
  const lastPage = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const current = request.pageNumber ?? 1;
  let page = 1;
  if (request.action === 'PF8') {
    page = Math.min(current + 1, lastPage);
  } else if (request.action === 'PF7') {
    page = Math.max(current - 1, 1);
  }
  const start = (page - 1) * PAGE_SIZE;
  return listResponse(rows.slice(start, start + PAGE_SIZE), {
    pageNumber: page,
    nextPage: page < lastPage,
  });
}

/**
 * :purpose: Publish the current router location so a drill-through or an exit is
 *     observable without stubbing ``react-router``.
 * :returns: the rendered location probe.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

/**
 * :purpose: Render the screen inside the shared 24x80 shell and a memory router,
 *     flushing the entry list request.
 * :returns: the Testing Library render result.
 */
async function renderScreen(): Promise<RenderResult> {
  let rendered!: RenderResult;
  await act(async () => {
    rendered = render(
      <MemoryRouter initialEntries={[LIST_ROUTE]}>
        <Layout>
          <Routes>
            <Route path={LIST_ROUTE} element={<TranListPage />} />
            <Route
              path={`${TRANSACTION_VIEW_ROUTE}:tranId`}
              element={<div data-testid="tran-view-screen" />}
            />
            <Route path={MENU_ROUTE} element={<div data-testid="menu-screen" />} />
          </Routes>
          <LocationProbe />
        </Layout>
      </MemoryRouter>,
    );
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
  return rendered;
}

/**
 * :purpose: The row lines currently displayed by the row table.
 * :returns: the ``tbody`` rows, in display order.
 */
function dataRows(): HTMLTableRowElement[] {
  const body = screen.getByRole('table').querySelector('tbody');
  return body === null ? [] : Array.from(body.querySelectorAll('tr'));
}

/**
 * :purpose: The transaction ids of the displayed row lines.
 * :returns: the ``Transaction ID`` column value of every displayed row.
 */
function rowTranIds(): string[] {
  return dataRows().map((row) => row.cells[1]?.textContent ?? '');
}

/**
 * :purpose: The row-selection flag fields of the displayed row lines.
 * :returns: one ``SEL000n`` input per displayed row, in display order.
 */
function selectionFields(): HTMLInputElement[] {
  return dataRows().flatMap((row) =>
    Array.from(row.querySelectorAll<HTMLInputElement>('input')),
  );
}

/**
 * :purpose: Look up a line-24 function key by its legend text.
 * :param label: the legend text, for example ``F8=Forward``.
 * :returns: the function-key control.
 */
function pfKey(label: string): HTMLElement {
  return screen.getByRole('button', { name: label });
}

/**
 * :purpose: Read the path the router currently points at.
 * :returns: the current path name.
 */
function currentPath(): string {
  return screen.getByTestId('location').textContent ?? '';
}

/** :purpose: Take the ENTER turn through the submit control, flushing any request. */
async function pressEnter(): Promise<void> {
  const submit = pfKey(SUBMIT_LABEL);
  await act(async () => {
    fireEvent.click(submit);
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Activate a function key through its physical AID key, as a 3270
 *     terminal would, flushing any resulting request.
 * :param key: the DOM keyboard key name, for example ``F8``.
 */
async function pressKey(key: string): Promise<void> {
  await act(async () => {
    fireEvent.keyDown(document, { key });
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Type a value into the ``TRNIDIN`` browse filter.
 * :param value: the value to place in the field.
 */
function typeFilter(value: string): void {
  fireEvent.change(screen.getByLabelText(FILTER_LABEL), { target: { value } });
}

/**
 * :purpose: Place a selection flag on a displayed row.
 * :param rowIndex: zero-based index of the row on the current page.
 * :param flag: the one-character flag to enter.
 */
function selectRow(rowIndex: number, flag: string): void {
  fireEvent.change(selectionFields()[rowIndex], { target: { value: flag } });
}

beforeEach(() => {
  listTransactionsMock.mockReset();
  signonMock.mockReset();
  listTransactionsMock.mockImplementation((request) => Promise.resolve(browse(request)));
  sessionStorage.clear();
  act(() => {
    setSession(TEST_USER, 'U');
  });
});

afterEach(() => {
  act(() => {
    setSession(null, null);
  });
  sessionStorage.clear();
});

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

describe('TranListPage — screen frame (COTRN00 / CT00 / COTRN00C)', () => {
  it('publishes the transaction id, program name and titles into the shell', async () => {
    await renderScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CT00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COTRN00C');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    expect(screen.getByRole('region', { name: 'List Transactions' })).toBeInTheDocument();
  });

  it('renders the five mapset column captions and the line-21 selection hint', async () => {
    await renderScreen();

    expect(screen.getByRole('columnheader', { name: 'Sel' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Transaction ID' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Date' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Description' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Amount' })).toBeInTheDocument();
    expect(screen.getByText(SELECTION_HINT)).toBeInTheDocument();
  });

  it('reads the first forward page from the top of the file on entry', async () => {
    await renderScreen();

    expect(listTransactionsMock).toHaveBeenCalledTimes(1);
    expect(listTransactionsMock).toHaveBeenCalledWith({});
    expect(screen.getByText('Page:')).toBeInTheDocument();
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
  });

  it('renders under the seeded authenticated session', async () => {
    const { container } = await renderScreen();

    expect(container.querySelector('.screen')).toHaveAttribute('data-authenticated', 'true');
  });
});

describe('TranListPage — ten rows per page (COTRN00 SEL0001..SEL0010)', () => {
  it('displays EXACTLY 10 data rows on page 1 out of 22 available rows', async () => {
    await renderScreen();

    expect(mockedRows).toHaveLength(22);
    expect(dataRows()).toHaveLength(10);
  });

  it('displays rows 1-10 in browse order and withholds row 11', async () => {
    await renderScreen();

    expect(rowTranIds()).toEqual(tranIdRange(1, 10));
    expect(screen.queryByText(tranIdOf(11))).toBeNull();
  });

  it('renders one 1-character SEL000n flag field per displayed row', async () => {
    await renderScreen();

    const fields = selectionFields();
    expect(fields).toHaveLength(10);
    expect(fields.map((field) => field.name)).toEqual([
      'SEL0001',
      'SEL0002',
      'SEL0003',
      'SEL0004',
      'SEL0005',
      'SEL0006',
      'SEL0007',
      'SEL0008',
      'SEL0009',
      'SEL0010',
    ]);
    fields.forEach((field) => {
      expect(field).toHaveAttribute('maxlength', '1');
      expect(field).toHaveValue('');
    });
  });

  it('renders the id, date, description and signed amount of a row verbatim', async () => {
    await renderScreen();

    const cells = dataRows()[0].cells;
    expect(cells[1]).toHaveTextContent(tranIdOf(1));
    expect(cells[2]).toHaveTextContent('01/01/22');
    expect(cells[3]).toHaveTextContent('PURCHASE 0001');
    expect(cells[4]).toHaveTextContent('10.01');
    expect(cells[4]).toHaveClass('amount');
    expect(dataRows()[1].cells[4]).toHaveTextContent('-20.02');
  });
});

describe('TranListPage — PF7 / PF8 paging', () => {
  it('pages forward with F8 to rows 11-20', async () => {
    await renderScreen();

    await pressKey('F8');

    expect(rowTranIds()).toEqual(tranIdRange(11, 20));
    expect(dataRows()).toHaveLength(10);
    expect(screen.getByTestId('page-number')).toHaveTextContent('2');
  });

  it('pages back to page 1 with F7 after paging forward', async () => {
    await renderScreen();

    await pressKey('F8');
    await pressKey('F7');

    expect(rowTranIds()).toEqual(tranIdRange(1, 10));
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
  });

  it('keeps F7 live on page 1 and re-reads the same page when it is pressed', async () => {
    await renderScreen();

    // ``PROCESS-PF7-KEY`` is reached on every page: at the top of the file it
    // re-reads the page already displayed, so the key is never withdrawn.
    expect(pfKey(PF7_LABEL)).toBeEnabled();
    expect(pfKey(PF8_LABEL)).toBeEnabled();

    await pressKey('F7');

    expect(rowTranIds()).toEqual(tranIdRange(1, 10));
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
    expect(listTransactionsMock).toHaveBeenCalledTimes(2);
  });

  it('keeps F8 live on the last page and re-reads the partial page', async () => {
    await renderScreen();

    await pressKey('F8');
    await pressKey('F8');

    expect(rowTranIds()).toEqual(tranIdRange(21, 22));
    expect(screen.getByTestId('page-number')).toHaveTextContent('3');
    expect(pfKey(PF8_LABEL)).toBeEnabled();
    expect(pfKey(PF7_LABEL)).toBeEnabled();

    await pressKey('F8');

    expect(rowTranIds()).toEqual(tranIdRange(21, 22));
    expect(screen.getByTestId('page-number')).toHaveTextContent('3');
    expect(listTransactionsMock).toHaveBeenCalledTimes(4);
  });

  it('pages forward when the F8 legend control itself is activated', async () => {
    await renderScreen();

    await act(async () => {
      fireEvent.click(pfKey(PF8_LABEL));
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(rowTranIds()).toEqual(tranIdRange(11, 20));
  });
});

describe('TranListPage — Search Tran ID filter (TRNIDIN)', () => {
  it('exposes a 16-character TRNIDIN filter behind the mapset prompt', async () => {
    await renderScreen();

    const filter = screen.getByLabelText(FILTER_LABEL);
    expect(filter).toHaveAttribute('maxlength', String(TRAN_ID_WIDTH));
    expect(filter).toHaveAttribute('id', FILTER_FIELD_NAME);
    expect(filter).toHaveAttribute('name', FILTER_FIELD_NAME);
    expect(filter).toHaveValue('');
  });

  it('forwards a numeric filter verbatim as the browse starting id', async () => {
    await renderScreen();

    typeFilter(tranIdOf(5));
    await pressEnter();

    expect(listTransactionsMock).toHaveBeenCalledTimes(2);
    expect(listTransactionsMock).toHaveBeenLastCalledWith({ tranIdFilter: tranIdOf(5) });
  });

  it('rejects a non-numeric filter with the verbatim numeric-class message', async () => {
    await renderScreen();

    typeFilter('ABCDEF');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_TRAN_ID_NUMERIC);
    expect(listTransactionsMock).toHaveBeenCalledTimes(1);
  });

  it('re-reads from the top of the file when the filter is left empty', async () => {
    await renderScreen();

    typeFilter('');
    await pressEnter();

    expect(listTransactionsMock).toHaveBeenCalledTimes(2);
    expect(listTransactionsMock).toHaveBeenLastCalledWith({});
  });
});

describe('TranListPage — row selection validation', () => {
  it('drills through to the transaction-view screen when a row is flagged S', async () => {
    await renderScreen();

    selectRow(0, 'S');
    await pressEnter();

    expect(tranIdOf(1)).toHaveLength(TRAN_ID_WIDTH);
    expect(currentPath()).toBe(`${TRANSACTION_VIEW_ROUTE}${tranIdOf(1)}`);
    expect(screen.getByTestId('tran-view-screen')).toBeInTheDocument();
  });

  it('accepts the lower-case flag s as the legacy EVALUATE does', async () => {
    await renderScreen();

    selectRow(2, 's');
    await pressEnter();

    expect(currentPath()).toBe(`${TRANSACTION_VIEW_ROUTE}${tranIdOf(3)}`);
  });

  it('rejects any other flag with the verbatim selection message and stays put', async () => {
    await renderScreen();

    selectRow(0, 'X');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_INVALID_SELECTION);
    expect(currentPath()).toBe(LIST_ROUTE);
    expect(listTransactionsMock).toHaveBeenCalledTimes(1);
  });

  it('acts on the first flagged row when several rows carry a flag', async () => {
    await renderScreen();

    selectRow(4, 'S');
    selectRow(1, 'S');
    await pressEnter();

    expect(currentPath()).toBe(`${TRANSACTION_VIEW_ROUTE}${tranIdOf(2)}`);
  });

  it('clears a pending rejection when the browse pages forward', async () => {
    await renderScreen();

    selectRow(0, 'X');
    await pressEnter();
    expect(screen.getByRole('alert')).toHaveTextContent(MSG_INVALID_SELECTION);

    await pressKey('F8');

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(rowTranIds()).toEqual(tranIdRange(11, 20));
  });
});

describe('TranListPage — line-24 function keys', () => {
  it('publishes exactly the ENTER / F3 / F7 / F8 legend of the mapset', async () => {
    await renderScreen();

    const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });
    // COTRN00.bms line 24 reads
    // 'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'.
    expect(within(toolbar).getAllByRole('button')).toHaveLength(4);
    expect(within(toolbar).getByRole('button', { name: SUBMIT_LABEL })).toBeInTheDocument();
    expect(within(toolbar).getByRole('button', { name: PF3_LABEL })).toBeInTheDocument();
    expect(within(toolbar).getByRole('button', { name: PF7_LABEL })).toBeInTheDocument();
    expect(within(toolbar).getByRole('button', { name: PF8_LABEL })).toBeInTheDocument();
  });

  it('exits to the main menu when the F3 legend control is activated', async () => {
    await renderScreen();

    await act(async () => {
      fireEvent.click(pfKey(PF3_LABEL));
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(currentPath()).toBe(MENU_ROUTE);
    expect(screen.getByTestId('menu-screen')).toBeInTheDocument();
  });

  it('exits to the main menu through the physical F3 key', async () => {
    await renderScreen();

    await pressKey('F3');

    expect(currentPath()).toBe(MENU_ROUTE);
  });
});

describe('TranListPage — request outcomes', () => {
  it('surfaces a failed browse in the line-23 message region', async () => {
    listTransactionsMock.mockReset();
    listTransactionsMock.mockRejectedValue(new ApiError(500, MSG_LOOKUP_FAILED));

    await renderScreen();

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_LOOKUP_FAILED);
    expect(screen.getByTestId('tran-list-empty')).toHaveTextContent(EMPTY_ROW_TEXT);
  });

  it('surfaces a server message in the single line-23 message field', async () => {
    listTransactionsMock.mockReset();
    listTransactionsMock.mockResolvedValue(listResponse([], { message: MSG_TOP_OF_PAGE }));

    await renderScreen();

    // COTRN00 declares one ERRMSG field, so every legacy message — boundary
    // announcements included — is carried by that single region.
    expect(screen.getByRole('alert')).toHaveTextContent(MSG_TOP_OF_PAGE);
    expect(infoBanner()).toBeNull();
  });

  it('reports an empty result and keeps both paging keys live', async () => {
    listTransactionsMock.mockReset();
    listTransactionsMock.mockResolvedValue(listResponse([]));

    await renderScreen();

    expect(screen.getByTestId('tran-list-empty')).toHaveTextContent(EMPTY_ROW_TEXT);
    expect(selectionFields()).toHaveLength(0);
    // ``PROCESS-PF7-KEY`` / ``PROCESS-PF8-KEY`` are always reached; the service,
    // not the screen, decides that there is nothing further to read.
    expect(pfKey(PF7_LABEL)).toBeEnabled();
    expect(pfKey(PF8_LABEL)).toBeEnabled();
  });
});

