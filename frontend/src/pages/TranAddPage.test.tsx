/**
 * TranAddPage tests
 * =================
 *
 * :purpose: Verify the add-transaction screen of CICS transaction ``CT02``
 *     (program ``COTRN02C``, mapset ``app/bms/COTRN02.bms``): the entry fields
 *     under their verbatim captions, the verbatim amount/date hints, the
 *     ``COTRN02C`` required-field edits, the ``(Y/N)`` confirmation gate, the
 *     server-assigned 16-digit transaction id (the request carries no
 *     ``tranId``), the cancel path, the rejected-add message, and the line-24
 *     function keys ``ENTER=Continue`` / ``F3=Back`` / ``F4=Clear`` / ``F5=Copy Last Tran.``.
 * :output: Jest assertions only; the suite writes no artifacts.
 * :note: ``../api`` is replaced by a synthetic module, so neither axios nor the
 *     Vite ``import.meta`` build environment is ever evaluated. Because the
 *     runtime is native ESM, the page and the screen shell are imported
 *     dynamically after the mock is registered, and the module registry is never
 *     reset so they share one React instance with the statically imported
 *     Testing Library.
 */
import { render, screen, fireEvent, act } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe / it /
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  TranAddRequestDto,
  TranAddResponseDto,
  TranKeyResponseDto,
  TranViewResponseDto,
} from '../types';

/** The ``ApiErrorResponse`` member the page reads (``error.body?.message``). */
interface ApiErrorBody {
  message?: string;
}

/**
 * :purpose: Structural stand-in for the real ``ApiError`` of
 *     ``frontend/src/api/client.ts``, passed through the synthetic ``../api`` so
 *     ``useApi``'s ``instanceof`` narrowing and the page's error resolution behave
 *     exactly as in production without loading the real client.
 * :param status: HTTP status of the failed call.
 * :param message: Normalized error text.
 * :param body: Standardized error body, when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the HTTP ``409`` conflict.
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

/** Stable mock for the ``../api`` ``addTransaction`` named export. */
const addTransactionMock =
  jest.fn<(request: TranAddRequestDto) => Promise<TranAddResponseDto>>();

/**
 * Stable mock for the ``../api`` ``resolveTransactionKeys`` named export — the
 * ``VALIDATE-INPUT-KEY-FIELDS`` cross-reference read, which the source performs before
 * any data field is examined and which writes the counterpart key back onto the map.
 */
const resolveTransactionKeysMock =
  jest.fn<(acctId: string, tranCardNum: string) => Promise<TranKeyResponseDto>>();

/** Stable mock for the ``../api`` ``getLastTransaction`` named export (F5=Copy). */
const getLastTransactionMock = jest.fn<() => Promise<TranViewResponseDto>>();

/** The card the cross-reference holds for :data:`RESOLVED_ACCT_ID`. */
const RESOLVED_CARD_NUM = '4111111111111111';

/** The account the cross-reference holds for :data:`RESOLVED_CARD_NUM`. */
const RESOLVED_ACCT_ID = '00000000011';

/**
 * Stable mock for the ``../api`` ``signon`` named export. ``useSession`` — reached
 * through both the hooks barrel and the screen shell — imports it statically, and
 * a native-ESM namespace must expose every imported name.
 */
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
  getLastTransaction: getLastTransactionMock,
  __esModule: true,
  addTransaction: addTransactionMock,
  resolveTransactionKeys: resolveTransactionKeysMock,
  signon: signonMock,
  ApiError,
}));

type LayoutComponent = (typeof import('../components/Layout'))['default'];
type TranAddPageComponent = (typeof import('./TranAddPage'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let Layout: LayoutComponent;
let TranAddPage: TranAddPageComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  ({ default: Layout } = await import('../components/Layout'));
  ({ default: TranAddPage } = await import('./TranAddPage'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

/** Route the add screen is mounted at. */
const ADD_ROUTE = '/transactions/add';

/** Route ``F3=Back`` returns to (``COTRN02C`` ``RETURN-TO-PREV-SCREEN``). */
const LIST_ROUTE = '/transactions';

/** Signed-on user seeded into the session store for every test. */
const SIGNED_ON_USER = 'USER0001';

/** 16-digit zero-padded id the service assigns from its database sequence. */
const SERVER_TRAN_ID = '0000000000000123';

/** Default successful add response; the id is server-assigned. */
const ADD_RESPONSE: TranAddResponseDto = {
  tranId: SERVER_TRAN_ID,
  message: null,
};

/** Verbatim ``COTRN02`` field captions, keyed by the field they label. */
const LABELS = {
  acctId: 'Enter Acct #:',
  cardNum: 'Card #:',
  typeCd: 'Type CD:',
  categoryCd: 'Category CD:',
  source: 'Source:',
  description: 'Description:',
  amount: 'Amount:',
  origDate: 'Orig Date:',
  procDate: 'Proc Date:',
  merchantId: 'Merchant ID:',
  merchantName: 'Merchant Name:',
  merchantCity: 'Merchant City:',
  merchantZip: 'Merchant Zip:',
  confirm: 'You are about to add this transaction. Please confirm :',
} as const;

/** Verbatim ``COTRN02C`` line-23 literals asserted by this suite. */
const MESSAGES = {
  acctOrCardRequired: 'Account or Card Number must be entered...',
  acctIdNumeric: 'Account ID must be Numeric...',
  acctIdNotFound: 'Account ID NOT found...',
  cardNumberNotFound: 'Card Number NOT found...',
  categoryCdEmpty: 'Category CD can NOT be empty...',
  descriptionEmpty: 'Description can NOT be empty...',
  amountEmpty: 'Amount can NOT be empty...',
  amountFormat: 'Amount should be in format -99999999.99',
  merchantCityEmpty: 'Merchant City can NOT be empty...',
  confirmRequired: 'Confirm to add this transaction...',
  invalidYesNo: 'Invalid value. Valid values are (Y/N)...',
  unableToAdd: 'Unable to Add Transaction...',
} as const;

/** Verbatim BMS line-15 hints. */
const AMOUNT_HINT = '(-99999999.99)';
const DATE_HINT = '(YYYY-MM-DD)';

/**
 * A fully valid screen in ``COTRN2AI`` field order. The amount keeps its signed
 * eight-digit / two-decimal string shape and the dates their ``YYYY-MM-DD`` wire
 * form, so no value is ever coerced to a number by the test.
 */
const VALID_ENTRY: ReadonlyArray<readonly [string, string]> = [
  [LABELS.acctId, '00000000011'],
  [LABELS.typeCd, '01'],
  [LABELS.categoryCd, '5001'],
  [LABELS.source, 'POS'],
  [LABELS.description, 'GROCERY STORE PURCHASE'],
  [LABELS.amount, '-00000100.00'],
  [LABELS.origDate, '2026-07-15'],
  [LABELS.procDate, '2026-07-16'],
  [LABELS.merchantId, '000123456'],
  [LABELS.merchantName, 'GLOBAL GROCERS'],
  [LABELS.merchantCity, 'SEATTLE'],
  [LABELS.merchantZip, '98101'],
];

/** The record ``F5=Copy Last Tran.`` reads, in the shape the view endpoint returns. */
const LAST_TRANSACTION: TranViewResponseDto = {
  tranId: '0000000000000042',
  tranTypeCd: '02',
  tranCatCd: '5002',
  tranSource: 'POS TERM',
  tranDesc: 'COPIED LAST TRANSACTION',
  tranAmt: '-100.00',
  tranOrigTs: '2026-07-20-11.22.33.000000',
  tranProcTs: '2026-07-21-11.22.33.000000',
  tranMerchantId: '000999888',
  tranMerchantName: 'COPY MERCHANT',
  tranMerchantCity: 'PORTLAND',
  tranMerchantZip: '97201',
  tranCardNum: RESOLVED_CARD_NUM,
};

/**
 * The request the page posts for :data:`VALID_ENTRY` confirmed with ``Y``. It carries no
 * ``tranId``, and it carries the card number the cross-reference read wrote back onto the
 * map (``MOVE XREF-CARD-NUM TO CARDNINI``) even though the operator supplied the account.
 */
const EXPECTED_REQUEST: TranAddRequestDto = {
  acctId: '00000000011',
  tranCardNum: RESOLVED_CARD_NUM,
  tranTypeCd: '01',
  tranCatCd: '5001',
  tranSource: 'POS',
  tranDesc: 'GROCERY STORE PURCHASE',
  tranAmt: '-00000100.00',
  tranOrigTs: '2026-07-15',
  tranProcTs: '2026-07-16',
  tranMerchantId: '000123456',
  tranMerchantName: 'GLOBAL GROCERS',
  tranMerchantCity: 'SEATTLE',
  tranMerchantZip: '98101',
  confirm: 'Y',
};

/**
 * :purpose: Compile-time guard that the add contract carries no client-supplied
 *     transaction id — this assignment stops compiling the moment ``tranId`` is
 *     added to ``TranAddRequestDto``.
 */
type RequestDeclaresTranId = 'tranId' extends keyof TranAddRequestDto
  ? true
  : false;
const requestDeclaresTranId: RequestDeclaresTranId = false;

/**
 * :purpose: Compile-time guard that the server-assigned id arrives on the add
 *     response contract.
 */
type ResponseDeclaresTranId = 'tranId' extends keyof TranAddResponseDto
  ? true
  : false;
const responseDeclaresTranId: ResponseDeclaresTranId = true;

/**
 * :purpose: Render the add screen inside the shared shell and a routed tree, so
 *     the line-23 message region and the line-24 key bar are present and
 *     ``F3=Back`` has a destination to reach.
 */
function renderScreen(): void {
  render(
    <MemoryRouter initialEntries={[ADD_ROUTE]}>
      <Layout>
        <Routes>
          <Route path={ADD_ROUTE} element={<TranAddPage />} />
          <Route
            path={LIST_ROUTE}
            element={<div data-testid="tranListRoute">Transaction List</div>}
          />
        </Routes>
      </Layout>
    </MemoryRouter>,
  );
}

/**
 * :purpose: Type a value into the field carrying a caption.
 * :param label: Verbatim caption of the field.
 * :param value: Value to place in the field.
 */
function enterField(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

/**
 * :purpose: Fill a leading run of :data:`VALID_ENTRY`, so a test can stop at the
 *     exact ``COTRN02C`` edit it exercises.
 * :param fieldCount: Number of leading fields to fill; all of them by default.
 */
function fillEntry(fieldCount: number = VALID_ENTRY.length): void {
  for (const [label, value] of VALID_ENTRY.slice(0, fieldCount)) {
    enterField(label, value);
  }
}

/** :purpose: Activate the ``ENTER=Continue`` key. */
function pressEnter(): void {
  fireEvent.click(screen.getByRole('button', { name: 'ENTER=Continue' }));
}

/**
 * :purpose: Activate ``ENTER=Continue`` and let the posted request settle.
 * :returns: A promise resolving once the add round trip has been applied.
 */
async function pressEnterAndSettle(): Promise<void> {
  await act(async () => {
    pressEnter();
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns. ENTER now opens
    // with the VALIDATE-INPUT-KEY-FIELDS cross-reference read, so the pass resolves over
    // several microtask hops — the lookup, its state commit, then the data-field pass —
    // and one hop is no longer enough to reach the line-23 outcome.
    for (let hop = 0; hop < 8; hop += 1) {
      await Promise.resolve();
    }
  });
}

/**
 * :purpose: Assert the verbatim text of the line-23 error region.
 * :param message: Expected message, compared character-for-character.
 */
function expectErrorLine(message: string): void {
  expect(screen.getByRole('alert').textContent).toBe(message);
}

/**
 * :purpose: Assert the verbatim text of the line-23 informational region.
 * :param message: Expected message, compared character-for-character.
 */
function expectInfoLine(message: string): void {
  expect(infoBanner()?.textContent).toBe(message);
}

beforeEach(async () => {
  addTransactionMock.mockReset();
  addTransactionMock.mockResolvedValue(ADD_RESPONSE);
  getLastTransactionMock.mockReset();
  getLastTransactionMock.mockResolvedValue(LAST_TRANSACTION);
  resolveTransactionKeysMock.mockReset();
  resolveTransactionKeysMock.mockImplementation((acctId, tranCardNum) =>
    Promise.resolve({
      acctId: acctId === '' ? RESOLVED_ACCT_ID : acctId,
      tranCardNum: tranCardNum === '' ? RESOLVED_CARD_NUM : tranCardNum,
    }),
  );
  signonMock.mockReset();
  await seedSignedOnSession(SIGNED_ON_USER, 'U');
});

afterEach(async () => {
  await seedSignedOutSession();
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

describe('TranAddPage — screen fields and verbatim hints', () => {
  it('renders every COTRN02 entry field under its verbatim caption', () => {
    renderScreen();

    for (const label of Object.values(LABELS)) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
    expect(screen.getByRole('form', { name: 'Add Transaction' })).toBeInTheDocument();
    expect(screen.getByText('(or)')).toBeInTheDocument();
  });

  it('publishes the CT02 / COTRN02C screen chrome into the shared shell', () => {
    renderScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CT02');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COTRN02C');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
  });

  it('shows the verbatim amount hint and both verbatim date hints', () => {
    renderScreen();

    expect(screen.getByText(AMOUNT_HINT)).toBeInTheDocument();
    expect(screen.getAllByText(DATE_HINT)).toHaveLength(2);
    expect(screen.getByLabelText(LABELS.amount)).toHaveAccessibleDescription(
      AMOUNT_HINT,
    );
    expect(screen.getByLabelText(LABELS.origDate)).toHaveAccessibleDescription(
      DATE_HINT,
    );
    expect(screen.getByLabelText(LABELS.procDate)).toHaveAccessibleDescription(
      DATE_HINT,
    );
    expect(screen.getByLabelText(LABELS.confirm)).toHaveAccessibleDescription(
      '(Y/N)',
    );
  });

  it('starts with an empty screen and an empty line-23 region', () => {
    renderScreen();

    for (const [label] of VALID_ENTRY) {
      expect(screen.getByLabelText(label)).toHaveValue('');
    }
    expect(screen.getByLabelText(LABELS.cardNum)).toHaveValue('');
    expect(screen.getByLabelText(LABELS.confirm)).toHaveValue('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
  });
});

/**
 * The ``VALIDATE-INPUT-KEY-FIELDS`` / ``VALIDATE-INPUT-DATA-FIELDS`` stop points
 * of ``COTRN02C``, each paired with the number of leading :data:`VALID_ENTRY`
 * fields that reaches it.
 */
const REQUIRED_FIELD_CASES: ReadonlyArray<readonly [number, string]> = [
  [0, MESSAGES.acctOrCardRequired],
  [2, MESSAGES.categoryCdEmpty],
  [4, MESSAGES.descriptionEmpty],
  [5, MESSAGES.amountEmpty],
  [10, MESSAGES.merchantCityEmpty],
];

describe('TranAddPage — required-field edits (COTRN02C)', () => {
  for (const [filledFields, message] of REQUIRED_FIELD_CASES) {
    it(`stops the pass with "${message}"`, async () => {
      renderScreen();
      fillEntry(filledFields);

      await pressEnterAndSettle();

      expectErrorLine(message);
      expect(addTransactionMock).not.toHaveBeenCalled();
    });
  }

  it('repositions the cursor onto the first field that failed its edit', async () => {
    renderScreen();

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.acctOrCardRequired);
    expect(screen.getByLabelText(LABELS.acctId)).toHaveFocus();
  });
});

describe('TranAddPage — (Y/N) confirmation gate', () => {
  it('validates on the first ENTER, asks to confirm, and posts nothing yet', async () => {
    renderScreen();
    fillEntry();

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.confirmRequired);
    expect(addTransactionMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText(LABELS.confirm)).toHaveFocus();
  });

  it('rejects a confirmation value that is neither Y nor N', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'X');

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.invalidYesNo);
    expect(addTransactionMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText(LABELS.confirm)).toHaveValue('X');
  });

  it('marks the control the rejection sends the cursor to', async () => {
    // `COTRN02C` ends a failed edit with `MOVE -1 TO <field>L`, so the cursor names the
    // field the row-23 message is about rather than leaving the operator to guess that
    // something failed. It contains no `MOVE DFHRED`, so the field is not recoloured.
    // The turn is awaited because the key-field edit that precedes the confirm edit is a
    // lookup, so the rejection is published once that round trip has settled -- exactly
    // as the sibling case above awaits it.
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'X');

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.invalidYesNo);
    const confirm = screen.getByLabelText(LABELS.confirm);
    expect(confirm).toBeInvalid();
    expect(confirm).toHaveClass('field');
    expect(confirm).not.toHaveClass('fieldError');
    expect(screen.getByLabelText(LABELS.acctId)).not.toBeInvalid();
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
  });

  it('accepts the lower-case y confirmation and forwards it verbatim', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'y');

    await pressEnterAndSettle();

    expect(addTransactionMock).toHaveBeenCalledTimes(1);
    expect(addTransactionMock.mock.calls[0][0].confirm).toBe('y');
  });
});

describe('TranAddPage — server-generated transaction id', () => {
  it('posts a request with no tranId property', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    await pressEnterAndSettle();

    expect(addTransactionMock).toHaveBeenCalledTimes(1);
    const request = addTransactionMock.mock.calls[0][0];
    expect(request).not.toHaveProperty('tranId');
    expect(Object.keys(request)).not.toContain('tranId');
    expect(request).toStrictEqual(EXPECTED_REQUEST);
  });

  it('keeps the monetary amount a string of the entered shape', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    await pressEnterAndSettle();

    const request = addTransactionMock.mock.calls[0][0];
    expect(typeof request.tranAmt).toBe('string');
    expect(request.tranAmt).toBe('-00000100.00');
    expect(request.tranOrigTs).toBe('2026-07-15');
    expect(request.tranProcTs).toBe('2026-07-16');
  });

  it('declares no tranId on the request contract and one on the response', () => {
    expect(requestDeclaresTranId).toBe(false);
    expect(responseDeclaresTranId).toBe(true);
  });

  it('replaces a standing rejection on the next turn and unmarks the field it faulted', async () => {
    // Line 23 holds one message and one only, belonging to the CURRENT turn: a 3270
    // repaints it on every SEND MAP. A rejection left standing beside a screen the
    // operator has since corrected would attribute the fault to values that no longer
    // carry it, and would leave the corrected control still announced as invalid.
    renderScreen();
    fillEntry();
    enterField(LABELS.amount, 'not-a-number');

    await pressEnterAndSettle();
    expectErrorLine(MESSAGES.amountFormat);
    expect(screen.getByLabelText(LABELS.amount)).toHaveAttribute('aria-invalid', 'true');

    enterField(LABELS.amount, '-00000100.00');
    await pressEnterAndSettle();

    // The corrected value now clears every data edit, so the turn reaches the CONFIRMI
    // evaluation and publishes its own prompt in place of the stale rejection.
    expectErrorLine(MESSAGES.confirmRequired);
    expect(screen.getByLabelText(LABELS.amount)).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText(LABELS.confirm)).toHaveAttribute('aria-invalid', 'true');
  });

  it('marks a rejected entry field invalid without reddening it', () => {
    // COTRN02C contains no `MOVE DFHRED` anywhere: the add screen never recolours a
    // field, so a rejected entry is marked for assistive technology alone. Painting a
    // red box would be observable output the program does not produce (AAP 0.7.2 Rule 5).
    renderScreen();

    expect(
      Array.from(document.querySelectorAll('.fieldError')),
    ).toHaveLength(0);
  });

  it('surfaces the server-assigned tranId and clears the screen', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    await pressEnterAndSettle();

    expectInfoLine(
      `Transaction added successfully.  Your Tran ID is ${SERVER_TRAN_ID}.`,
    );
    for (const [label] of VALID_ENTRY) {
      expect(screen.getByLabelText(label)).toHaveValue('');
    }
    expect(screen.getByLabelText(LABELS.confirm)).toHaveValue('');
  });

  it('prefers a server message that already carries the assigned id', async () => {
    const serverMessage = `Transaction ${SERVER_TRAN_ID} posted.`;
    addTransactionMock.mockResolvedValue({
      tranId: SERVER_TRAN_ID,
      message: serverMessage,
    });
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    await pressEnterAndSettle();

    expectInfoLine(serverMessage);
  });

  it('posts the card key when only the card number was entered', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.acctId, '');
    enterField(LABELS.cardNum, '4111111111111111');
    enterField(LABELS.confirm, 'Y');

    await pressEnterAndSettle();

    const request = addTransactionMock.mock.calls[0][0];
    expect(request.tranCardNum).toBe('4111111111111111');
    // ``MOVE XREF-ACCT-ID TO ACTIDINI``: reading by card writes the account back onto
    // the map, so the key the operator did not supply is on the screen and travels
    // with the request.
    expect(request.acctId).toBe(RESOLVED_ACCT_ID);
    expect(resolveTransactionKeysMock).toHaveBeenCalledWith('', '4111111111111111');
    expect(request).not.toHaveProperty('tranId');
  });

  it('surfaces a rejected add on the line-23 error region', async () => {
    addTransactionMock.mockRejectedValue(
      new ApiError(400, MESSAGES.unableToAdd, {
        message: MESSAGES.unableToAdd,
      }),
    );
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    await pressEnterAndSettle();

    expect(addTransactionMock).toHaveBeenCalledTimes(1);
    expectErrorLine(MESSAGES.unableToAdd);
    expect(screen.getByLabelText(LABELS.amount)).toHaveValue('-00000100.00');
  });
});

describe('TranAddPage — cancelling the add', () => {
  it('posts nothing when the confirmation is N', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'N');

    await pressEnterAndSettle();

    expect(addTransactionMock).not.toHaveBeenCalled();
    expectErrorLine(MESSAGES.confirmRequired);
    expect(screen.getByLabelText(LABELS.confirm)).toHaveValue('');
  });

  it('posts nothing when the confirmation is lower-case n', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'n');

    await pressEnterAndSettle();

    expect(addTransactionMock).not.toHaveBeenCalled();
    expectErrorLine(MESSAGES.confirmRequired);
  });

  it('keeps the entered fields on screen after a cancelled add', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'N');

    await pressEnterAndSettle();

    expect(screen.getByLabelText(LABELS.amount)).toHaveValue('-00000100.00');
    expect(screen.getByLabelText(LABELS.description)).toHaveValue(
      'GROCERY STORE PURCHASE',
    );
  });
});

describe('TranAddPage — line-24 function keys', () => {
  it('offers ENTER=Continue, F3=Back, F4=Clear and F5=Copy Last Tran.', () => {
    renderScreen();

    // COTRN02.bms line 24 reads
    // 'ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.'.
    expect(screen.getByRole('button', { name: 'ENTER=Continue' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'F3=Back' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'F4=Clear' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'F5=Copy Last Tran.' }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(4);
  });

  it('F3=Back returns to the transaction list route', () => {
    renderScreen();

    fireEvent.click(screen.getByRole('button', { name: 'F3=Back' }));

    expect(screen.getByTestId('tranListRoute')).toBeInTheDocument();
    expect(screen.queryByLabelText(LABELS.acctId)).not.toBeInTheDocument();
  });

  it('the physical F3 key also returns to the transaction list route', () => {
    renderScreen();

    fireEvent.keyDown(document, { key: 'F3' });

    expect(screen.getByTestId('tranListRoute')).toBeInTheDocument();
  });

  it('F4=Clear empties every field and the line-23 region', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'N');
    await pressEnterAndSettle();
    expectErrorLine(MESSAGES.confirmRequired);

    fireEvent.click(screen.getByRole('button', { name: 'F4=Clear' }));

    for (const [label] of VALID_ENTRY) {
      expect(screen.getByLabelText(label)).toHaveValue('');
    }
    expect(screen.getByLabelText(LABELS.confirm)).toHaveValue('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(addTransactionMock).not.toHaveBeenCalled();
  });

  it('the physical ENTER key drives the confirmed add', async () => {
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    await act(async () => {
      fireEvent.keyDown(document, { key: 'Enter' });
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(addTransactionMock).toHaveBeenCalledTimes(1);
    expect(addTransactionMock.mock.calls[0][0]).toStrictEqual(EXPECTED_REQUEST);
  });
});

describe('TranAddPage — in-flight duplicate-add guard', () => {
  it('closes every entry field while the add is in flight and posts once for repeated ENTER', async () => {
    let acknowledge!: (value: TranAddResponseDto) => void;
    addTransactionMock.mockReturnValueOnce(
      new Promise<TranAddResponseDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    renderScreen();
    fillEntry();
    enterField(LABELS.confirm, 'Y');

    // ENTER opens with the VALIDATE-INPUT-KEY-FIELDS cross-reference read, so the POST
    // is only reached once that has settled.
    await act(async () => {
      pressEnter();
      for (let hop = 0; hop < 8; hop += 1) {
        await Promise.resolve();
      }
    });

    // ``ATTRB=ASKIP`` for the whole in-flight interval: the operator cannot retype a
    // money-moving entry while the POST is outstanding.
    for (const [label] of VALID_ENTRY) {
      expect(screen.getByLabelText(label)).toBeDisabled();
    }
    expect(screen.getByLabelText(LABELS.confirm)).toBeDisabled();
    // The keyboard is locked for the same interval, so the row-24 legend renders
    // inactive too rather than inviting an AID the screen would discard.
    for (const label of [
      'ENTER=Continue',
      'F3=Back',
      'F4=Clear',
      'F5=Copy Last Tran.',
    ]) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled();
    }

    // A second ENTER, from the legend and from the physical key, must not post again.
    await act(async () => {
      pressEnter();
      fireEvent.keyDown(document, { key: 'Enter' });
      for (let hop = 0; hop < 8; hop += 1) {
        await Promise.resolve();
      }
    });
    expect(addTransactionMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge(ADD_RESPONSE);
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(addTransactionMock).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText(LABELS.acctId)).toBeEnabled();
  });
});


/* ------------------------------------------------------------------ */
/* VALIDATE-INPUT-KEY-FIELDS runs before VALIDATE-INPUT-DATA-FIELDS   */
/* ------------------------------------------------------------------ */

describe('TranAddPage — key-field edit order (PROCESS-ENTER-KEY)', () => {
  it('reports a key that is not on file before any empty data field', async () => {
    // PROCESS-ENTER-KEY performs VALIDATE-INPUT-KEY-FIELDS and only then
    // VALIDATE-INPUT-DATA-FIELDS, and the cross-reference reads live inside the first
    // paragraph. An account that does not exist is therefore reported even though every
    // data field is still empty.
    resolveTransactionKeysMock.mockRejectedValueOnce(
      new ApiError(404, MESSAGES.acctIdNotFound),
    );
    renderScreen();
    enterField(LABELS.acctId, '00000000099');

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.acctIdNotFound);
    expect(addTransactionMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText(LABELS.acctId)).toHaveFocus();
  });

  it('places the cursor on the card field when the card is not on file', async () => {
    // READ-CCXREF-FILE ends its NOTFND branch with ``MOVE -1 TO CARDNINL``.
    resolveTransactionKeysMock.mockRejectedValueOnce(
      new ApiError(404, MESSAGES.cardNumberNotFound),
    );
    renderScreen();
    enterField(LABELS.cardNum, '9999999999999999');

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.cardNumberNotFound);
    expect(screen.getByLabelText(LABELS.cardNum)).toHaveFocus();
  });

  it('does not read the cross-reference for a key that fails its shape edit', async () => {
    renderScreen();
    enterField(LABELS.acctId, 'ABCDEFGHIJK');

    await pressEnterAndSettle();

    expectErrorLine(MESSAGES.acctIdNumeric);
    expect(resolveTransactionKeysMock).not.toHaveBeenCalled();
  });

  it('writes the counterpart key back onto the map', async () => {
    renderScreen();
    enterField(LABELS.acctId, '00000000011');

    await pressEnterAndSettle();

    expect(screen.getByLabelText(LABELS.cardNum)).toHaveValue(RESOLVED_CARD_NUM);
  });
});

describe('TranAddPage — F5=Copy Last Tran. (COPY-LAST-TRAN-DATA)', () => {
  /** :purpose: Activate the ``F5=Copy Last Tran.`` key and flush its round trips. */
  async function pressCopyLastAndSettle(): Promise<void> {
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'F5=Copy Last Tran.' }));
      for (let hop = 0; hop < 12; hop += 1) {
        await Promise.resolve();
      }
    });
  }

  it('refuses a copy with no key, before reading anything', async () => {
    // The paragraph opens with PERFORM VALIDATE-INPUT-KEY-FIELDS.
    renderScreen();

    await pressCopyLastAndSettle();

    expectErrorLine(MESSAGES.acctOrCardRequired);
    expect(getLastTransactionMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText(LABELS.acctId)).toHaveFocus();
  });

  it('copies the last transaction and then asks for the confirmation', async () => {
    // The paragraph closes with PERFORM PROCESS-ENTER-KEY, so the copied screen comes
    // to rest on the confirmation gate rather than looking already submitted.
    renderScreen();
    enterField(LABELS.acctId, '00000000011');

    await pressCopyLastAndSettle();

    expect(getLastTransactionMock).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText(LABELS.description)).toHaveValue(LAST_TRANSACTION.tranDesc);
    expectErrorLine(MESSAGES.confirmRequired);
    expect(addTransactionMock).not.toHaveBeenCalled();
    expect(screen.getByLabelText(LABELS.confirm)).toHaveFocus();
  });
});
