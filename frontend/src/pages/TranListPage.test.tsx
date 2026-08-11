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
  listTransactions: listTransactionsMock,
  signon: signonMock,
  ApiError,
}));

/** Rejection message for a row-selection flag other than ``S`` (``COTRN00C``). */
const MSG_INVALID_SELECTION = 'Invalid selection. Valid value is S';

/** Rejection message for a non-numeric browse filter (``COTRN00C``). */
const MSG_TRAN_ID_NUMERIC = 'Tran ID must be Numeric ...';

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
type SessionHarness = typeof import('../testing/sessionHarness');

let TranListPage: TranListPageModule['default'];
let Layout: LayoutModule['default'];
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the screen, the shell and the hooks
  // all bind to the mocked ``../api``; no module reset, so they share React with
  // Testing Library.
  ({ default: TranListPage } = await import('./TranListPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
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
 * :purpose: The transaction ids of the row slots the browse actually FILLED. The map has
 *     ten slots on every send and ``COTRN00C`` blanks the ones it did not read, so a
 *     short page paints blank slots that carry no id.
 * :returns: the non-blank ``Transaction ID`` column values, in display order.
 */
function populatedRowTranIds(): string[] {
  return rowTranIds().filter((tranId) => tranId !== '');
}

/**
 * :purpose: The row slots left blank by the browse.
 * :returns: the count of row lines whose data cells are all empty.
 */
function blankRowCount(): number {
  return dataRows().filter((row) =>
    [1, 2, 3, 4].every((cellIndex) => (row.cells[cellIndex]?.textContent ?? '') === ''),
  ).length;
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

beforeEach(async () => {
  listTransactionsMock.mockReset();
  signonMock.mockReset();
  listTransactionsMock.mockImplementation((request) => Promise.resolve(browse(request)));
  sessionStorage.clear();
  await seedSignedOnSession(TEST_USER, 'U');
});

afterEach(async () => {
  await seedSignedOutSession();
  sessionStorage.clear();
});


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

    expect(populatedRowTranIds()).toEqual(tranIdRange(21, 22));
    expect(screen.getByTestId('page-number')).toHaveTextContent('3');
    expect(pfKey(PF8_LABEL)).toBeEnabled();
    expect(pfKey(PF7_LABEL)).toBeEnabled();

    await pressKey('F8');

    expect(populatedRowTranIds()).toEqual(tranIdRange(21, 22));
    expect(screen.getByTestId('page-number')).toHaveTextContent('3');
    expect(listTransactionsMock).toHaveBeenCalledTimes(4);
  });

  /**
   * :purpose: ``COTRN00.bms`` declares ``SEL0001``..``SEL0010`` and their row companions at
   *     fixed ``POS`` values, and ``COTRN00C`` L289-L292 blanks all ten before filling the
   *     ones it read, so a two-row last page is still a ten-row grid. Rendering only the
   *     filled slots collapsed the grid and pulled the line-21 instruction literal and the
   *     line-24 legend up the frame by the height of every missing row.
   */
  it('keeps all ten row slots on a partial last page, the unused ones blank', async () => {
    await renderScreen();

    await pressKey('F8');
    await pressKey('F8');

    expect(dataRows()).toHaveLength(10);
    expect(populatedRowTranIds()).toEqual(tranIdRange(21, 22));
    expect(blankRowCount()).toBe(8);
    // The slots the QA reproduction probed by id are present and enterable, exactly as
    // the unprotected mapset fields are.
    expect(selectionFields()).toHaveLength(10);
    for (const slot of [5, 6, 7, 8, 9, 10]) {
      const field = document.getElementById(`SEL${String(slot).padStart(4, '0')}`);
      expect(field).not.toBeNull();
      expect(field).toBeEnabled();
    }
  });

  /**
   * :purpose: A flag typed into a blank slot is ignored on the 3270 — the selection branch
   *     requires the row's ``TRNID0n`` to be non-blank as well — so it neither navigates
   *     nor faults its control.
   */
  it('ignores a selection flag typed into a blank row slot', async () => {
    await renderScreen();

    await pressKey('F8');
    await pressKey('F8');

    const blankSlot = selectionFields()[6];
    fireEvent.change(blankSlot, { target: { value: 'X' } });

    expect(blankSlot).not.toHaveAttribute('aria-invalid');

    await pressKey('Enter');

    expect(screen.queryByRole('alert')).toBeNull();
    expect(currentPath()).toBe(LIST_ROUTE);
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

  it('browses from the top when the field holds only spaces (EQUAL SPACES)', async () => {
    await renderScreen();

    typeFilter('    ');
    await pressEnter();

    expect(listTransactionsMock).toHaveBeenCalledTimes(2);
    expect(listTransactionsMock).toHaveBeenLastCalledWith({});
  });

  it('refuses a tab instead of browsing the whole file and reporting success', async () => {
    // A tab is neither SPACES nor LOW-VALUES, so L209 tests it for NUMERIC and it
    // fails. Trimming it first turned a discarded filter into an unfiltered browse
    // that reported success.
    await renderScreen();

    typeFilter('\t');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_TRAN_ID_NUMERIC);
    expect(listTransactionsMock).toHaveBeenCalledTimes(1);
  });

  it('refuses a padded value instead of silently browsing its trimmed self', async () => {
    await renderScreen();

    typeFilter(' 15 ');
    await pressEnter();

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_TRAN_ID_NUMERIC);
    expect(listTransactionsMock).toHaveBeenCalledTimes(1);
  });

  it('cannot hold more characters than TRNIDIN declares, so none are dropped later', async () => {
    // Nineteen digits reached the service and overflowed the key parse. The field is
    // LENGTH=16, and what the field holds is what the browse receives.
    await renderScreen();

    typeFilter('1234567890123456789012345');
    expect(screen.getByLabelText(FILTER_LABEL)).toHaveValue('1234567890123456');

    await pressEnter();

    expect(listTransactionsMock).toHaveBeenLastCalledWith({
      tranIdFilter: '1234567890123456',
    });
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

  it('holds the table to the summed width of its column rules so no column collapses', async () => {
    // Under `table-layout: fixed` the columns carrying an explicit width are allocated
    // FIRST and the width-less last column takes only the remainder, so in a container
    // narrower than the fixed columns it is allocated ZERO -- its cells clip to nothing,
    // and a zero-width column adds nothing to the scroll range, so scrolling never
    // reveals it either. Measured at 375 and 320 CSS px: the Amount column had width 0
    // and its values were unreachable at maximum container scroll.
    await renderScreen();

    const table = document.querySelector('table.dataTable');
    // 6, 19, 11, 29, 12 -- the mapset's own column PITCH, the distance from one row-9
    // dashed rule to the next (POS=(9,2), (9,8), (9,27), (9,38), (9,67)), which is the
    // same arithmetic the <colgroup> uses.
    expect(table).toHaveStyle({ minWidth: '77ch' });

    const cols = Array.from(document.querySelectorAll('colgroup col'));
    expect(cols).toHaveLength(5);
    expect(cols.slice(0, 4).map((col) => col.getAttribute('style'))).toEqual([
      'width: 6ch;',
      'width: 19ch;',
      'width: 11ch;',
      'width: 29ch;',
    ]);
    // The last column stays width-less on purpose: a container wider than the floor
    // hands it the slack, keeping the right-aligned amount against the frame's edge.
    expect(cols[4].getAttribute('style')).toBeNull();
  });

  it('publishes the selection message on the PHYSICAL ENTER key, not only the legend button', async () => {
    // The two activation paths reach the same handler by different routes: the legend
    // button calls the prop it was handed on the current render, while the physical AID
    // key is dispatched from the document listener through the published key array. A
    // key array published from a PASSIVE effect lagged one commit behind the fields it
    // acts on, so the keyboard turn read an empty selection map, fell through to the
    // browse, and cleared the typed character with no message -- while the same turn
    // taken with the button worked. Both paths are asserted for that reason.
    await renderScreen();

    selectRow(0, 'X');
    await pressKey('Enter');

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_INVALID_SELECTION);
    expect(currentPath()).toBe(LIST_ROUTE);
    expect(listTransactionsMock).toHaveBeenCalledTimes(1);
    // The character the operator typed is still in its box: COTRN00C moves nothing
    // into the SEL fields, so the RECEIVE value is echoed straight back by the send.
    expect(selectionFields()[0]).toHaveValue('X');
  });

  it('marks the rejected selection control invalid without reddening it', async () => {
    // COTRN00C contains no `MOVE DFHRED` at all -- the whole program never recolours a
    // field -- so a rejected selection is marked for assistive technology only. A red
    // box here would be observable output the program does not produce.
    await renderScreen();

    selectRow(0, 'X');
    await pressKey('Enter');

    expect(selectionFields()[0]).toHaveAttribute('aria-invalid', 'true');
    expect(selectionFields()[0].className).not.toContain('fieldError');
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

    const toolbar = screen.getByRole('group', { name: 'Function keys' });
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
    // The ten row slots stay on the frame and no placeholder is invented: COTRN00C leaves
    // the ten row fields at LOW-VALUES and publishes its message on line 23.
    expect(dataRows()).toHaveLength(10);
    expect(populatedRowTranIds()).toEqual([]);
    expect(screen.queryByTestId('tran-list-empty')).toBeNull();
  });

  it('keeps the displayed page on screen when a later browse fails', async () => {
    await renderScreen();
    const rowsBefore = rowTranIds();
    expect(rowsBefore.length).toBeGreaterThan(0);
    const pageBefore = screen.getByTestId('page-number').textContent;

    // COTRN00C re-sends the map with ``SET SEND-ERASE-NO TO TRUE`` whenever it reports a
    // browse it did not perform, so the rows already painted and the page number beside
    // them stay put while line 23 carries the message. Discarding them would throw away
    // the operator's browse position on a message that never claimed it had moved.
    listTransactionsMock.mockRejectedValueOnce(new ApiError(500, MSG_LOOKUP_FAILED));
    await act(async () => {
      fireEvent.click(pfKey(PF8_LABEL));
      await Promise.resolve();
    });

    expect(screen.getByRole('alert')).toHaveTextContent(MSG_LOOKUP_FAILED);
    expect(rowTranIds()).toEqual(rowsBefore);
    expect(screen.getByTestId('page-number').textContent).toBe(pageBefore);
  });

  it('surfaces a server message in the single line-23 message field', async () => {
    listTransactionsMock.mockReset();
    listTransactionsMock.mockResolvedValue(listResponse([], { message: MSG_TOP_OF_PAGE }));

    await renderScreen();

    // COTRN00 declares one ERRMSG field, so every legacy message — boundary
    // announcements included — is carried by that single region, in the RED the mapset
    // declares statically. A boundary announcement is not a failure, so it is announced
    // politely (``role="status"``) rather than as an alert while keeping that colour.
    expect(document.getElementById('screenMessageLine')).toHaveTextContent(MSG_TOP_OF_PAGE);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('reports an empty result and keeps both paging keys live', async () => {
    listTransactionsMock.mockReset();
    listTransactionsMock.mockResolvedValue(listResponse([]));

    await renderScreen();

    // The body carries no invented "nothing found" literal; the empty result is
    // reported by the service's own line-23 message alone.
    expect(screen.queryByTestId('tran-list-empty')).toBeNull();
    expect(screen.queryByText(/no transactions to display/i)).toBeNull();
    expect(dataRows()).toHaveLength(10);
    expect(blankRowCount()).toBe(10);
    // ``PROCESS-PF7-KEY`` / ``PROCESS-PF8-KEY`` are always reached; the service,
    // not the screen, decides that there is nothing further to read.
    expect(pfKey(PF7_LABEL)).toBeEnabled();
    expect(pfKey(PF8_LABEL)).toBeEnabled();
  });

  it('states no result while the browse is still outstanding', async () => {
    // The browse never settles inside this test, so the assertions describe the
    // screen exactly as the operator sees it while the terminal is waiting.
    listTransactionsMock.mockReset();
    listTransactionsMock.mockImplementation(
      () => new Promise<TranListResponseDto>(() => undefined),
    );

    render(
      <MemoryRouter initialEntries={[LIST_ROUTE]}>
        <Layout>
          <TranListPage />
        </Layout>
      </MemoryRouter>,
    );

    // The row region claims nothing: the browse has not answered, so a "no transactions"
    // literal would be a result the screen does not have -- and COTRN00C paints no such
    // literal in the body in any state, so no placeholder row exists to carry one.
    expect(screen.queryByTestId('tran-list-empty')).toBeNull();
    expect(screen.queryByText(/no transactions to display/i)).toBeNull();
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByText(/no transactions to display/i)).toBeNull();
    // The grid is already at its declared ten rows while the browse is outstanding, so it
    // does not change shape when the answer arrives; every slot is blank and inhibited.
    expect(dataRows()).toHaveLength(10);
    expect(blankRowCount()).toBe(10);
    for (const field of selectionFields()) {
      expect(field).toBeDisabled();
    }
  });
});

describe('TranListPage — COTRN00 browse-table contract', () => {
  it('paints the row-9 rule as the per-column hyphen runs the mapset declares', async () => {
    await renderScreen();

    // COTRN00 row 9: 3, 16, 8, 26 and 12 hyphens at columns 2, 8, 27, 38 and 67, with
    // the gaps between them blank -- not one continuous border across the table.
    const rule = document.querySelector('.dataTable__rule');
    expect(rule).not.toBeNull();
    expect(rule).toHaveAttribute('aria-hidden', 'true');
    expect(
      Array.from(rule?.querySelectorAll('td') ?? []).map((cell) => cell.textContent),
    ).toEqual(['-'.repeat(3), '-'.repeat(16), '-'.repeat(8), '-'.repeat(26), '-'.repeat(12)]);
  });

  it('renders the screen name NEUTRAL, as COTRN00 row 4 declares', async () => {
    await renderScreen();

    expect(screen.getByRole('heading', { name: 'List Transactions' })).toHaveClass('neutral');
  });

  it('puts the browse table in a labelled region the keyboard can enter', async () => {
    await renderScreen();

    // On a narrow viewport the trailing BMS columns fall outside the frame and hold no
    // focusable field of their own, so the scroll container carries the tab stop.
    const region = screen.getByRole('group', { name: 'Transaction list columns' });
    expect(region).toHaveClass('tableScroll');
    expect(region).toHaveAttribute('tabindex', '0');
    expect(region).toContainElement(screen.getByRole('table'));
  });
});
