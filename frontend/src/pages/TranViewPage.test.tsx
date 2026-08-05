/**
 * :module: TranViewPage.test
 * :purpose: Verify the transaction-detail workflow of :func:`TranViewPage` — the
 *     screen replacing BMS mapset ``app/bms/COTRN01.bms`` (map ``COTRN1A``, field
 *     contract ``app/cpy-bms/COTRN01.CPY``) and CICS program
 *     ``app/cbl/COTRN01C.cbl`` (transaction ``CT01``). Covers the
 *     route-parameter drill-through load, the read-only rendering of the
 *     thirteen protected (``ATTRB=ASKIP``) detail fields at their BMS widths, the
 *     ``TRNIDIN`` X(16) search field, the two verbatim validation messages, and
 *     the ENTER / F3 function keys including the exit route.
 * :output: Assertions only; the module exports nothing.
 * :note: ``../api`` and ``../components/Layout`` are replaced through
 *     ``jest.unstable_mockModule`` — the mocking API of Jest's native-ESM
 *     runtime, as used by the sibling suites — so no axios request and no Vite
 *     ``import.meta`` evaluation occurs, and the screen chrome the page
 *     publishes (line-23 message region, line-24 key legend) is captured for
 *     assertion instead of being discarded by the detached shell context.
 *     ``ApiError`` is passed through as a working error class so the
 *     ``instanceof`` checks in the page and in ``useApi`` behave unchanged.
 *     Application modules are imported dynamically, after the factories are
 *     registered.
 */
import { jest } from '@jest/globals';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { ComponentType } from 'react';
import { PfKeyAction } from '../types';
import type { Role, TranViewResponseDto } from '../types';

/**
 * :purpose: Error payload the api client normalizes onto ``ApiError.body``.
 * :field message: backend message text surfaced by the screen.
 */
interface ApiErrorBody {
  message?: string;
}

/**
 * :purpose: Working stand-in for the ``../api`` ``ApiError``, matching its
 *     constructor shape and public members so both the page and ``useApi``
 *     narrow it with ``instanceof`` exactly as they do in production.
 * :param status: HTTP status of the failed call.
 * :param message: axios-level failure message.
 * :param body: parsed error payload, when the backend returned one.
 * :param isOptimisticLockConflict: ``true`` for the HTTP ``409`` conflict.
 */
class ApiError extends Error {
  readonly status: number;

  readonly body?: ApiErrorBody;

  readonly isOptimisticLockConflict: boolean;

  constructor(
    status: number,
    message: string,
    body?: ApiErrorBody,
    isOptimisticLockConflict = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = isOptimisticLockConflict;
  }
}

/** ``GET /transactions/{id}`` (``COTRN01C`` ``READ-TRANSACT-FILE``). */
const getTransaction =
  jest.fn<(transactionId: string) => Promise<TranViewResponseDto>>();

/** Linked by ``useSession`` through the ``../hooks`` barrel; never called here. */
const signon = jest.fn();

jest.unstable_mockModule('../api', () => ({
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  getTransaction,
  signon,
  ApiError,
}));

/**
 * :purpose: One entry of the line-24 function-key legend a page publishes.
 * :field action: the AID / PF-key the entry represents.
 * :field label: legend text rendered on the key.
 * :field onActivate: handler invoked when the key fires.
 * :field enabled: ``false`` disables the key.
 */
interface CapturedPfKey {
  action: PfKeyAction;
  label: string;
  onActivate: () => void;
  enabled?: boolean;
}

/**
 * :purpose: The screen chrome a page publishes into the shared shell.
 * :field transactionId: 4-character CICS transaction id.
 * :field programName: legacy program name.
 * :field title01: first title line.
 * :field title02: second title line.
 * :field errorMessage: line-23 error text.
 * :field infoMessage: line-23 informational text.
 * :field pfKeys: line-24 function keys.
 */
interface CapturedChrome {
  transactionId?: string;
  programName?: string;
  title01?: string;
  title02?: string;
  errorMessage?: string;
  infoMessage?: string;
  pfKeys?: CapturedPfKey[];
}

/** Chrome most recently published by the screen under test. */
let publishedChrome: CapturedChrome = {};

/** Chrome handed back to the screen; it reads only ``setChrome``. */
const EMPTY_CHROME: CapturedChrome = {};

/**
 * :purpose: Record the chrome a page publishes. Declared once at module scope so
 *     its identity is stable across renders — the page lists it as a
 *     ``useEffect`` dependency.
 * :param next: the chrome being published.
 */
function setChrome(next: CapturedChrome): void {
  publishedChrome = next;
}

jest.unstable_mockModule('../components/Layout', () => ({
  __esModule: true,
  useScreenChrome: () => ({ chrome: EMPTY_CHROME, setChrome }),
}));

/** Route of the transaction list screen (``COTRN00`` / ``CT00``). */
const TRAN_LIST_ROUTE = '/transactions';

/** Drill-through route carrying the selected id (``CDEMO-CT01-TRN-SELECTED``). */
const TRAN_VIEW_ROUTE = '/transactions/:transactionId';

/** 16-digit transaction id supplied as the route parameter. */
const TRAN_ID = '0000000000000001';

/** Second 16-digit id used for the in-screen re-search. */
const OTHER_TRAN_ID = '0000000000000099';

/** Authenticated standard user seeded into the session store. */
const USER_ID = 'USER0001';

/** ``SEC-USR-TYPE`` ``'U'`` — standard (non-administrator) user. */
const USER_ROLE: Role = 'U';

/**
 * Wire payload of the mocked lookup. Money, identifiers, the four-digit category
 * code and the 26-character timestamps all travel as strings, so no numeric
 * coercion is asserted.
 */
const transactionDetail: TranViewResponseDto = {
  tranId: TRAN_ID,
  tranCardNum: '4111111111111111',
  tranTypeCd: '01',
  tranCatCd: '5001',
  tranSource: 'POS',
  tranDesc: 'GROCERY STORE PURCHASE',
  tranAmt: '-00000123.45',
  tranOrigTs: '2022-01-01-12.00.00.000000',
  tranProcTs: '2022-01-02-13.30.15.123456',
  tranMerchantId: '000123456',
  tranMerchantName: 'ACME GROCERY',
  tranMerchantCity: 'SEATTLE',
  tranMerchantZip: '98101',
};

/**
 * Every protected detail field as its exact BMS caption paired with the text it
 * must display for :data:`transactionDetail`. The ``Orig Date:`` / ``Proc Date:``
 * entries carry the X(10) date portion of the X(26) timestamps, reproducing the
 * COBOL ``MOVE`` into ``TORIGDT`` / ``TPROCDT``.
 */
const detailFields: ReadonlyArray<readonly [string, string]> = [
  ['Transaction ID:', TRAN_ID],
  ['Card Number:', '4111111111111111'],
  ['Type CD:', '01'],
  ['Category CD:', '5001'],
  ['Source:', 'POS'],
  ['Description:', 'GROCERY STORE PURCHASE'],
  ['Amount:', '-00000123.45'],
  ['Orig Date:', '2022-01-01'],
  ['Proc Date:', '2022-01-02'],
  ['Merchant ID:', '000123456'],
  ['Merchant Name:', 'ACME GROCERY'],
  ['Merchant City:', 'SEATTLE'],
  ['Merchant Zip:', '98101'],
];

let TranViewPage: ComponentType;
let __setSession: (user: string | null, role: Role | null) => void;

beforeAll(async () => {
  const sessionStore = await import('../hooks/useSession');
  __setSession = sessionStore.__setSession;
  const pageModule = await import('./TranViewPage');
  TranViewPage = pageModule.default;
});

beforeEach(() => {
  getTransaction.mockReset();
  publishedChrome = {};
  act(() => {
    __setSession(USER_ID, USER_ROLE);
  });
});

afterEach(() => {
  act(() => {
    __setSession(null, null);
  });
});

/**
 * :purpose: Render the screen under a router that supplies the
 *     ``:transactionId`` route parameter and also mounts the transaction-list
 *     route, so PF3 navigation is observable.
 * :param path: initial history entry, normally ``/transactions/<id>``.
 */
function renderTranViewPage(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={TRAN_LIST_ROUTE} element={<div data-testid="tran-list-screen" />} />
        <Route path={TRAN_VIEW_ROUTE} element={<TranViewPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * :purpose: Render the screen for :data:`TRAN_ID` and wait for the drill-through
 *     lookup to settle.
 */
async function renderLoadedScreen(): Promise<void> {
  getTransaction.mockResolvedValue(transactionDetail);
  renderTranViewPage(`${TRAN_LIST_ROUTE}/${TRAN_ID}`);
  await waitFor(() => {
    expect(getTransaction).toHaveBeenCalledTimes(1);
  });
}

/**
 * :purpose: Locate the unprotected ``TRNIDIN`` entry field.
 * :returns: the ``Enter Tran ID:`` input (BMS ``INITIAL='Enter Tran ID:'``).
 */
function searchField(): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>('Enter Tran ID:');
}

/**
 * :purpose: Locate a protected detail field by its BMS caption. ``DFHBMPRF``
 *     makes the field output only, so the screen renders a labelled output cell
 *     rather than a read-only entry field.
 * :param label: the caption rendered beside the field.
 * :returns: the display cell the caption names.
 */
function detailField(label: string): HTMLElement {
  return screen.getByLabelText(label);
}

/**
 * :purpose: Type a transaction id into the ``TRNIDIN`` field.
 * :param value: the text entered by the operator.
 */
function typeSearchTranId(value: string): void {
  fireEvent.change(searchField(), { target: { value } });
}

/**
 * :purpose: Read a function key from the chrome the screen last published.
 * :param action: the AID / PF-key to look up.
 * :returns: the published legend entry.
 */
function findPfKey(action: PfKeyAction): CapturedPfKey {
  const key = publishedChrome.pfKeys?.find((entry) => entry.action === action);
  if (key === undefined) {
    throw new Error(`TranViewPage published no ${action} function key`);
  }
  return key;
}

/**
 * :purpose: Activate a published function key and flush the resulting state
 *     updates, including any request the handler starts.
 * :param action: the AID / PF-key to activate.
 */
async function activatePfKey(action: PfKeyAction): Promise<void> {
  const key = findPfKey(action);
  await act(async () => {
    key.onActivate();
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

describe('TranViewPage — load by route parameter', () => {
  it('looks up the transaction named by the route parameter', async () => {
    await renderLoadedScreen();

    const [requestedTranId] = getTransaction.mock.calls[0];
    expect(typeof requestedTranId).toBe('string');
    expect(requestedTranId).toHaveLength(16);
    expect(requestedTranId).toBe(TRAN_ID);
  });

  it('renders the screen title and prefills the search field with the id', async () => {
    await renderLoadedScreen();

    expect(screen.getByRole('heading', { name: 'View Transaction' })).toBeInTheDocument();
    expect(searchField()).toHaveValue(TRAN_ID);
  });

  it('renders every detail field read-only with its BMS caption and value', async () => {
    await renderLoadedScreen();
    await waitFor(() => {
      expect(detailField('Transaction ID:').textContent).toBe(TRAN_ID);
    });

    detailFields.forEach(([label, displayed]) => {
      const field = detailField(label);
      expect(field.textContent).toBe(displayed);
      // An output cell takes no operator input at all: it is not an entry field.
      expect(field.tagName).toBe('DD');
    });
  });

  it('displays the X(10) date portion of the X(26) timestamps', async () => {
    await renderLoadedScreen();
    await waitFor(() => {
      expect(detailField('Orig Date:').textContent).toBe('2022-01-01');
    });

    expect(detailField('Proc Date:').textContent).toBe('2022-01-02');
    expect(transactionDetail.tranOrigTs).toHaveLength(26);
    expect(transactionDetail.tranProcTs).toHaveLength(26);
  });

  it('publishes the CT01 / COTRN01C screen identity', async () => {
    await renderLoadedScreen();

    expect(publishedChrome.transactionId).toBe('CT01');
    expect(publishedChrome.programName).toBe('COTRN01C');
    expect(publishedChrome.title01).toBe(CCDA_TITLE01);
    expect(publishedChrome.title02).toBe(CCDA_TITLE02);
    expect(publishedChrome.errorMessage).toBe('');
  });
});

describe('TranViewPage — search field', () => {
  it('exposes the unprotected TRNIDIN field capped at sixteen characters', async () => {
    await renderLoadedScreen();

    const field = searchField();
    expect(field).toHaveAttribute('maxlength', '16');
    expect(field).not.toHaveAttribute('readonly');
  });

  it('accepts an entered transaction id', async () => {
    await renderLoadedScreen();

    typeSearchTranId(OTHER_TRAN_ID);

    expect(searchField()).toHaveValue(OTHER_TRAN_ID);
  });
});

describe('TranViewPage — validation messages', () => {
  it('reports the empty-id message and issues no lookup for a blank id', async () => {
    await renderLoadedScreen();

    typeSearchTranId('');
    await activatePfKey(PfKeyAction.Enter);

    expect(publishedChrome.errorMessage).toBe('Tran ID can NOT be empty...');
    expect(getTransaction).toHaveBeenCalledTimes(1);
  });

  it('reports the empty-id message for a whitespace-only id', async () => {
    await renderLoadedScreen();

    typeSearchTranId('    ');
    await activatePfKey(PfKeyAction.Enter);

    expect(publishedChrome.errorMessage).toBe('Tran ID can NOT be empty...');
    expect(getTransaction).toHaveBeenCalledTimes(1);
  });

  it('reports the not-found message when the lookup returns 404', async () => {
    getTransaction.mockRejectedValue(new ApiError(404, 'Request failed with status code 404'));
    renderTranViewPage(`${TRAN_LIST_ROUTE}/${TRAN_ID}`);

    await waitFor(() => {
      expect(publishedChrome.errorMessage).toBe('Transaction ID NOT found...');
    });
    expect(getTransaction).toHaveBeenCalledWith(TRAN_ID);
    expect(detailField('Transaction ID:').textContent).toBe('');
  });

  it('surfaces the backend message for a failure other than 404', async () => {
    getTransaction.mockRejectedValue(
      new ApiError(500, 'Request failed with status code 500', {
        message: 'Unable to lookup Transaction...',
      }),
    );
    renderTranViewPage(`${TRAN_LIST_ROUTE}/${TRAN_ID}`);

    await waitFor(() => {
      expect(publishedChrome.errorMessage).toBe('Unable to lookup Transaction...');
    });
  });

  it('surfaces the request message when the failure carries no body', async () => {
    getTransaction.mockRejectedValue(new ApiError(503, 'Network Error'));
    renderTranViewPage(`${TRAN_LIST_ROUTE}/${TRAN_ID}`);

    await waitFor(() => {
      expect(publishedChrome.errorMessage).toBe('Network Error');
    });
  });

  it('clears the message once a valid id is searched again', async () => {
    await renderLoadedScreen();

    typeSearchTranId('');
    await activatePfKey(PfKeyAction.Enter);
    expect(publishedChrome.errorMessage).toBe('Tran ID can NOT be empty...');

    typeSearchTranId(OTHER_TRAN_ID);
    await activatePfKey(PfKeyAction.Enter);

    expect(getTransaction).toHaveBeenCalledTimes(2);
    expect(getTransaction).toHaveBeenLastCalledWith(OTHER_TRAN_ID);
    expect(publishedChrome.errorMessage).toBe('');
  });
});

describe('TranViewPage — function keys', () => {
  it('publishes the four line-24 keys the mapset legends', async () => {
    await renderLoadedScreen();

    // COTRN01.bms line 24 reads
    // 'ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran.'.
    expect(publishedChrome.pfKeys).toHaveLength(4);
    expect(findPfKey(PfKeyAction.Enter).label).toBe('ENTER=Fetch');
    expect(findPfKey(PfKeyAction.PF3).label).toBe('F3=Back');
    expect(findPfKey(PfKeyAction.PF4).label).toBe('F4=Clear');
    expect(findPfKey(PfKeyAction.PF5).label).toBe('F5=Browse Tran.');
  });

  it('looks up the entered id when ENTER is activated', async () => {
    await renderLoadedScreen();

    typeSearchTranId(OTHER_TRAN_ID);
    await activatePfKey(PfKeyAction.Enter);

    expect(getTransaction).toHaveBeenCalledTimes(2);
    expect(getTransaction).toHaveBeenLastCalledWith(OTHER_TRAN_ID);
    expect(publishedChrome.errorMessage).toBe('');
  });

  it('returns to the transaction list when F3 is activated', async () => {
    await renderLoadedScreen();

    await activatePfKey(PfKeyAction.PF3);

    expect(screen.getByTestId('tran-list-screen')).toBeInTheDocument();
    expect(screen.queryByLabelText('Enter Tran ID:')).not.toBeInTheDocument();
  });
});
