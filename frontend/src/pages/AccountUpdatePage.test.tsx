/**
 * :module: AccountUpdatePage.test
 * :purpose: Verify the account-update workflow of BMS mapset ``app/bms/COACTUP.bms``
 *     (CICS transaction ``CAUP``, program ``COACTUPC``, ``PUT /accounts/{id}``): the
 *     read that captures the optimistic-lock ``version`` snapshot, the editable
 *     entry fields and their verbatim mapset captions, the ``1200-EDIT-MAP-INPUTS``
 *     validation messages, the HTTP ``409`` optimistic-lock conflict banner
 *     (``DATA-WAS-CHANGED-BEFORE-UPDATE``, AAP 0.6.2), the committed-update
 *     outcome, the HTTP ``401`` result, and the ``ENTER`` / ``F3`` / ``F5`` / ``F12``
 *     function keys of the line-24 legend.
 * :note: ``../api`` is replaced through ``jest.unstable_mockModule``, the module-mock
 *     entry point of Jest's native-ESM runtime, so no axios, no network, and no
 *     ``import.meta`` evaluation occur. The mocked ``ApiError`` is a passthrough
 *     whose instances expose ``status``, ``body`` and ``isOptimisticLockConflict``
 *     (``status === 409``), so the page's conflict branch and ``useApi``'s
 *     ``instanceof`` normalization both resolve against it. The page publishes its
 *     message line and function keys into the shared ``Layout`` shell through
 *     ``useScreenChrome``, so every render wraps the page in ``Layout``; per-field
 *     highlighting stays page-local and is asserted on the inputs themselves.
 * :note: A ``401`` is asserted here only on what this SCREEN owns — the message it
 *     surfaces and the record it does not display. The ``401`` -> ``/signon``
 *     transition belongs to the api client's response interceptor and the session
 *     store, neither of which is in this suite's path, and it is asserted where both
 *     are real: ``api/client.test.ts`` and ``AppComposition.test.tsx``.
 */

import { jest } from '@jest/globals';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import type {
  AccountUpdateRequestDto,
  AccountUpdateResponseDto,
  AccountViewResponseDto,
  ApiErrorResponse,
} from '../types';

/* ------------------------------------------------------------------ */
/* Frozen contract literals                                           */
/* ------------------------------------------------------------------ */

/** ``COACTUPC`` ``DATA-WAS-CHANGED-BEFORE-UPDATE`` (app/cbl/COACTUPC.cbl:L522). */
const OPTIMISTIC_LOCK_MESSAGE = 'Record changed by some one else. Please review';

/** ``COACTUPC`` ``CONFIRM-UPDATE-SUCCESS``. */
const UPDATE_SUCCESS_MESSAGE = 'Changes committed to database';

/** ``COACTUPC`` ``PROMPT-FOR-CHANGES``. */
const PROMPT_FOR_CHANGES_MESSAGE = 'Update account details presented above.';

/** ``COACTUPC`` ``PROMPT-FOR-CONFIRMATION``. */
const PROMPT_FOR_CONFIRMATION_MESSAGE = 'Changes validated.Press F5 to save';

/** Routed 11-digit account key under test. */
const ACCOUNT_ID = '00000000011';

/** Optimistic-lock version the read returns and the update must echo back. */
const LOADED_VERSION = 7;

/** Optimistic-lock version a committed update returns. */
const COMMITTED_VERSION = 8;

/** Signed-on user seeded into the session store. */
const SESSION_USER = 'USER0001';

/* ------------------------------------------------------------------ */
/* Mocked ``../api`` surface                                          */
/* ------------------------------------------------------------------ */

const getAccountMock =
  jest.fn<(accountId: string) => Promise<AccountViewResponseDto>>();

const updateAccountMock =
  jest.fn<
    (
      accountId: string,
      request: AccountUpdateRequestDto,
    ) => Promise<AccountUpdateResponseDto>
  >();

/**
 * ``POST /accounts/{id}/validate`` — the ENTER edit pass. It resolves by default, so a
 * test that does not care about the semantic edits sees ENTER succeed as before; a test
 * that does care makes it reject with the envelope the service would have returned.
 */
const validateAccountUpdateMock =
  jest.fn<(accountId: string, request: AccountUpdateRequestDto) => Promise<void>>();

const signonMock = jest.fn();

/**
 * :purpose: Passthrough stand-in for the client-normalized ``ApiError``, avoiding
 *     any load of the real ``../api/client`` (which pulls in axios and the Vite
 *     environment reader).
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: already-resolved error message.
 * :param body: standardized backend error body, when the response carried one.
 */
class ApiError extends Error {
  readonly status: number;

  readonly body?: ApiErrorResponse;

  readonly isOptimisticLockConflict: boolean;

  constructor(status: number, message: string, body?: ApiErrorResponse) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.isOptimisticLockConflict = status === 409;
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
  getAccount: getAccountMock,
  updateAccount: updateAccountMock,
  validateAccountUpdate: validateAccountUpdateMock,
  signon: signonMock,
  ApiError,
}));

/* ------------------------------------------------------------------ */
/* Modules loaded after the mock is registered                         */
/* ------------------------------------------------------------------ */

type AccountUpdatePageComponent = (typeof import('./AccountUpdatePage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let AccountUpdatePage: AccountUpdatePageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported here — not statically — so the page, ``useApi`` and ``useSession``
  // all bind to the mocked ``../api``. No module reset, so they share the single
  // React instance used by the statically imported Testing Library.
  ({ default: AccountUpdatePage } = await import('./AccountUpdatePage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

/* ------------------------------------------------------------------ */
/* Fixture                                                            */
/* ------------------------------------------------------------------ */

/**
 * :purpose: Account + customer record shaped exactly like the ``GET /accounts/{id}``
 *     payload. Every value satisfies the ``COACTUPC`` edit sequence, so a save from
 *     the untouched screen reaches the service; individual tests corrupt one field
 *     at a time to exercise a single edit.
 */
const ACCOUNT: AccountViewResponseDto = {
  acctId: ACCOUNT_ID,
  acctActiveStatus: 'Y',
  acctCurrBal: '1000.00',
  acctCreditLimit: '5000.00',
  acctCashCreditLimit: '500.00',
  acctOpenDate: '2020-01-15',
  acctExpiraionDate: '2027-01-14',
  acctReissueDate: '2024-01-15',
  acctCurrCycCredit: '100.00',
  acctCurrCycDebit: '200.00',
  acctGroupId: 'ZEROAPR',
  custId: '000000011',
  custFirstName: 'ALICE',
  custMiddleName: 'B',
  custLastName: 'ANDERSON',
  custAddrLine1: '100 MAIN ST',
  custAddrLine2: 'APT 2',
  custAddrLine3: 'SEATTLE',
  custAddrStateCd: 'WA',
  custAddrCountryCd: 'USA',
  custAddrZip: '98101',
  custPhoneNum1: '(206)555-0101',
  custPhoneNum2: '(206)555-0102',
  custSsn: '123456789',
  custGovtIssuedId: 'WA-DL-99887766',
  custDobYyyyMmDd: '1980-04-30',
  custEftAccountId: '1234567890',
  custPriCardHolderInd: 'Y',
  custFicoCreditScore: '750',
  version: LOADED_VERSION,
};

/** The refreshed record a committed ``PUT /accounts/{id}`` returns. */
const COMMITTED_ACCOUNT: AccountUpdateResponseDto = {
  ...ACCOUNT,
  version: COMMITTED_VERSION,
};

/* ------------------------------------------------------------------ */
/* Harness                                                            */
/* ------------------------------------------------------------------ */

/**
 * :purpose: Render the screen for an authenticated user at
 *     ``/accounts/:accountId/update`` inside the shared ``Layout`` shell, with the
 *     sibling routes the page navigates to declared so a transfer is observable.
 * :param accountId: the routed account key; defaults to the fixture account.
 */
async function renderScreen(accountId: string = ACCOUNT_ID): Promise<void> {
  await seedSignedOnSession(SESSION_USER, 'U');
  await act(async () => {
    render(
      <MemoryRouter initialEntries={[`/accounts/${accountId}/update`]}>
        <Routes>
          <Route
            path="/accounts/:accountId/update"
            element={
              <Layout>
                <AccountUpdatePage />
              </Layout>
            }
          />
          <Route path="/accounts/:accountId" element={<div data-testid="view-route" />} />
          <Route path="/menu" element={<div data-testid="menu-route" />} />
        </Routes>
      </MemoryRouter>,
    );
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Locate one entry field of the mapset. Every field carries its DTO
 *     member name as both ``id`` and ``name``, so the lookup also proves that
 *     wiring.
 * :param field: the DTO member name.
 * :returns: the entry field element.
 */
function fieldInput(field: string): HTMLInputElement {
  const element = document.getElementById(field);
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`No entry field rendered for "${field}"`);
  }
  return element;
}

/**
 * :purpose: Read a field's visible caption exactly as rendered, without the
 *     whitespace normalization the accessible-name queries apply, so the
 *     multi-space BMS captions can be compared character-for-character.
 * :param field: the DTO member name the caption is bound to.
 * :returns: the caption text, or an empty string when the field carries none.
 */
function captionOf(field: string): string {
  const label = document.querySelector<HTMLLabelElement>(`label[for="${field}"]`);
  return label?.textContent ?? '';
}

/**
 * :purpose: Assert a ``YYYY-MM-DD`` wire value is bound positionally to the three
 *     mapset segments that display it.
 * :param fields: the year, month and day field ids, in mapset order.
 * :param value: the wire date the segments carry.
 */
function expectDateSegments(
  [year, month, day]: [string, string, string],
  value: string,
): void {
  expect(fieldInput(year)).toHaveValue(value.slice(0, 4));
  expect(fieldInput(month)).toHaveValue(value.slice(5, 7));
  expect(fieldInput(day)).toHaveValue(value.slice(8, 10));
}

/**
 * :purpose: Assert a phone number is bound to the three mapset segments that
 *     display it, whatever punctuation the wire value carries.
 * :param fields: the area, prefix and line field ids, in mapset order.
 * :param value: the wire phone number the segments carry.
 */
function expectPhoneSegments(
  [area, prefix, line]: [string, string, string],
  value: string,
): void {
  const digits = value.replace(/\D/g, '');
  expect(fieldInput(area)).toHaveValue(digits.slice(0, 3));
  expect(fieldInput(prefix)).toHaveValue(digits.slice(3, 6));
  expect(fieldInput(line)).toHaveValue(digits.slice(6, 10));
}

/**
 * :purpose: Replace the whole value of an entry field.
 * :param user: the interaction session.
 * :param field: the DTO member name.
 * :param value: the replacement value; an empty string clears the field.
 */
async function retype(
  user: ReturnType<typeof userEvent.setup>,
  field: string,
  value: string,
): Promise<void> {
  await user.clear(fieldInput(field));
  if (value !== '') {
    await user.type(fieldInput(field), value);
  }
}

/** Credit limit typed by :func:`commit` when the caller changed nothing itself. */
const EDITED_CREDIT_LIMIT = '7500.00';

/**
 * :purpose: Drive the save ``COACTUPC`` requires. ``1200-EDIT-MAP-INPUTS`` runs on
 *     ENTER against a screen that has something changed, and L905-916 rewrites an
 *     F5 pressed in any other state back to ENTER, so the rewrite is reached only
 *     as ENTER followed by F5.
 * :param user: the interaction session.
 * :param changed: ``true`` when the caller has already altered a field.
 */
async function commit(
  user: ReturnType<typeof userEvent.setup>,
  changed = false,
): Promise<void> {
  if (!changed) {
    await retype(user, 'acctCreditLimit', EDITED_CREDIT_LIMIT);
  }
  await user.keyboard('{Enter}');
  await user.keyboard('{F5}');
}

/**
 * :purpose: The request body of one recorded ``updateAccount`` call.
 * :param index: zero-based call index.
 * :returns: the request body the page submitted.
 */
function submittedRequest(index: number): AccountUpdateRequestDto {
  const call = updateAccountMock.mock.calls[index];
  if (call === undefined) {
    throw new Error(`updateAccount was not called ${index + 1} time(s)`);
  }
  return call[1];
}

beforeEach(() => {
  getAccountMock.mockReset();
  updateAccountMock.mockReset();
  validateAccountUpdateMock.mockReset();
  signonMock.mockReset();
  getAccountMock.mockResolvedValue(ACCOUNT);
  updateAccountMock.mockResolvedValue(COMMITTED_ACCOUNT);
  // The service accepts the submission unless a test says otherwise.
  validateAccountUpdateMock.mockResolvedValue(undefined);
});

afterEach(async () => {
  // Return the shared session store to signed-out so no state leaks across tests.
  await seedSignedOutSession();
});

/* ------------------------------------------------------------------ */
/* Load and optimistic-lock version snapshot                          */
/* ------------------------------------------------------------------ */

/**
 * :purpose: The mapset's own INFOMSG field -- the NEUTRAL line the map declares ABOVE the
 *     line-23 ERRMSG region. It is matched on that field's class rather than on the role,
 *     both because the shared shell renders a visually hidden ``role="status"`` busy
 *     announcer and because the ERRMSG field itself carries ``role="status"`` on the
 *     screens whose programs green it.
 * :returns: the INFOMSG line, or ``null`` when the field is not rendered.
 */
function infoBanner(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.errorBanner--infoField[role="status"]');
}

/**
 * :purpose: Wait for the line-23 informational message to be rendered.
 * :returns: the informational banner once it appears.
 */
async function findInfoBanner(): Promise<HTMLElement> {
  await waitFor(() => {
    expect(infoBanner()).not.toBeNull();
  });
  return infoBanner() as HTMLElement;
}

/**
 * :purpose: Count the reserved marker columns that actually carry the blank-field `*`.
 *     The columns themselves are always rendered, which is what keeps a failed edit
 *     from moving any caption or entry field.
 * :returns: the number of marker columns holding a glyph.
 */
function markedMarkerCount(): number {
  return Array.from(
    document.querySelectorAll('span.accountUpdate__marker[aria-hidden="true"]'),
  ).filter((marker) => (marker.textContent ?? '') !== '').length;
}

describe('AccountUpdatePage — load and version snapshot', () => {
  it('reads the routed account and seeds the entry fields from the payload', async () => {
    await renderScreen();

    expect(getAccountMock).toHaveBeenCalledTimes(1);
    expect(getAccountMock).toHaveBeenCalledWith(ACCOUNT_ID);
    expect(screen.getByLabelText('Account Number :')).toHaveValue(ACCOUNT_ID);
    expect(fieldInput('acctActiveStatus')).toHaveValue(ACCOUNT.acctActiveStatus);
    expect(fieldInput('acctCreditLimit')).toHaveValue(ACCOUNT.acctCreditLimit);
    expect(fieldInput('custId')).toHaveValue(ACCOUNT.custId);
    expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CHANGES_MESSAGE);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('carries the version read at display time on the update request', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });
    expect(updateAccountMock).toHaveBeenCalledWith(ACCOUNT_ID, expect.anything());
    expect(submittedRequest(0).version).toBe(ACCOUNT.version);
    expect(submittedRequest(0).version).toBe(LOADED_VERSION);
  });

  it('submits the edited values with the misspelled acctExpiraionDate preserved', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'acctCreditLimit', '7500.00');
    await commit(user, true);

    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });
    const request = submittedRequest(0);
    expect(request.acctCreditLimit).toBe('7500.00');
    expect(request.acctExpiraionDate).toBe(ACCOUNT.acctExpiraionDate);
    expect(request.custFicoCreditScore).toBe(ACCOUNT.custFicoCreditScore);
    // Identity is derived from the request path, so the body carries no custId.
    expect(request).not.toHaveProperty('custId');
  });

  it('adopts the version the committed update returns for the next save', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);
    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });

    await commit(user);
    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(2);
    });

    expect(submittedRequest(0).version).toBe(LOADED_VERSION);
    expect(submittedRequest(1).version).toBe(COMMITTED_VERSION);
  });

  it('rejects a zero routed account key without reading and without a snapshot', async () => {
    await renderScreen('00000000000');

    expect(getAccountMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Account Number if supplied must be a 11 digit Non-Zero Number',
    );
    expect(fieldInput('acctActiveStatus')).toHaveValue('');
  });

  it('reports the missing snapshot when a save is attempted before a read', async () => {
    await renderScreen('00000000000');
    const user = userEvent.setup();

    await user.keyboard('{F5}');

    // COACTUPC L905-916 rewrites an F5 pressed outside the confirmation state back
    // to ENTER, so the unreadable key is reported and nothing is written.
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Account Number if supplied must be a 11 digit Non-Zero Number',
    );
    expect(updateAccountMock).not.toHaveBeenCalled();
  });
});

/* ------------------------------------------------------------------ */
/* Editable fields and verbatim mapset captions                       */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — editable fields and captions', () => {
  it('renders the screen and customer-section headings', async () => {
    await renderScreen();

    expect(
      screen.getByRole('heading', { name: 'Update Account', level: 3 }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Customer Details', level: 4 }),
    ).toBeInTheDocument();
  });

  it('captions every editable field verbatim, matching the view screen', async () => {
    await renderScreen();

    expect(captionOf('acctsid')).toBe('Account Number :');
    expect(captionOf('acctActiveStatus')).toBe('Active Y/N:');
    expect(captionOf('opnYear')).toBe('Opened :');
    expect(captionOf('acctCreditLimit')).toBe('Credit Limit        :');
    expect(captionOf('expYear')).toBe('Expiry :');
    expect(captionOf('acctCashCreditLimit')).toBe('Cash credit Limit   :');
    expect(captionOf('risYear')).toBe('Reissue:');
    expect(captionOf('acctCurrBal')).toBe('Current Balance     :');
    expect(captionOf('acctCurrCycCredit')).toBe('Current Cycle Credit:');
    expect(captionOf('acctGroupId')).toBe('Account Group:');
    expect(captionOf('acctCurrCycDebit')).toBe('Current Cycle Debit :');
    expect(captionOf('custId')).toBe('Customer id  :');
    expect(captionOf('actSsn1')).toBe('SSN:');
    expect(captionOf('dobYear')).toBe('Date of birth:');
    expect(captionOf('custFicoCreditScore')).toBe('FICO Score:');
    expect(captionOf('custFirstName')).toBe('First Name');
    expect(captionOf('custMiddleName')).toBe('Middle Name:');
    expect(captionOf('custLastName')).toBe('Last Name :');
    expect(captionOf('custAddrLine1')).toBe('Address:');
    expect(captionOf('custAddrStateCd')).toBe('State');
    expect(captionOf('custAddrZip')).toBe('Zip');
    expect(captionOf('custAddrLine3')).toBe('City');
    expect(captionOf('custAddrCountryCd')).toBe('Country');
    expect(captionOf('acsPh1A')).toBe('Phone 1:');
    expect(captionOf('custGovtIssuedId')).toBe('Government Issued Id Ref    :');
    expect(captionOf('acsPh2A')).toBe('Phone 2:');
    expect(captionOf('custEftAccountId')).toBe('EFT Account Id:');
    expect(captionOf('custPriCardHolderInd')).toBe('Primary Card Holder Y/N:');
  });

  it('names the uncaptioned second address line for assistive technology', async () => {
    await renderScreen();

    expect(captionOf('custAddrLine2')).toBe('');
    expect(fieldInput('custAddrLine2')).toHaveAccessibleName('Address Line 2');
    expect(fieldInput('custAddrLine2')).toHaveValue(ACCOUNT.custAddrLine2);
  });

  it('binds every editable field to its DTO value', async () => {
    await renderScreen();

    // COACTUP declares each date as three fields, so the wire ``YYYY-MM-DD``
    // string is bound positionally to its year / month / day segments.
    expectDateSegments(['opnYear', 'opnMon', 'opnDay'], ACCOUNT.acctOpenDate);
    expectDateSegments(['expYear', 'expMon', 'expDay'], ACCOUNT.acctExpiraionDate);
    expectDateSegments(['risYear', 'risMon', 'risDay'], ACCOUNT.acctReissueDate);
    expect(fieldInput('acctCashCreditLimit')).toHaveValue(ACCOUNT.acctCashCreditLimit);
    expect(fieldInput('acctCurrBal')).toHaveValue(ACCOUNT.acctCurrBal);
    expect(fieldInput('acctCurrCycCredit')).toHaveValue(ACCOUNT.acctCurrCycCredit);
    expect(fieldInput('acctCurrCycDebit')).toHaveValue(ACCOUNT.acctCurrCycDebit);
    expect(fieldInput('acctGroupId')).toHaveValue(ACCOUNT.acctGroupId);
    // SSN is three fields (``PIC 9(03)`` / ``9(02)`` / ``9(04)``).
    expect(fieldInput('actSsn1')).toHaveValue(ACCOUNT.custSsn.slice(0, 3));
    expect(fieldInput('actSsn2')).toHaveValue(ACCOUNT.custSsn.slice(3, 5));
    expect(fieldInput('actSsn3')).toHaveValue(ACCOUNT.custSsn.slice(5));
    expectDateSegments(['dobYear', 'dobMon', 'dobDay'], ACCOUNT.custDobYyyyMmDd);
    expect(fieldInput('custFicoCreditScore')).toHaveValue(
      String(ACCOUNT.custFicoCreditScore),
    );
    expect(fieldInput('custFirstName')).toHaveValue(ACCOUNT.custFirstName);
    expect(fieldInput('custMiddleName')).toHaveValue(ACCOUNT.custMiddleName);
    expect(fieldInput('custLastName')).toHaveValue(ACCOUNT.custLastName);
    expect(fieldInput('custAddrLine1')).toHaveValue(ACCOUNT.custAddrLine1);
    expect(fieldInput('custAddrLine3')).toHaveValue(ACCOUNT.custAddrLine3);
    expect(fieldInput('custAddrStateCd')).toHaveValue(ACCOUNT.custAddrStateCd);
    expect(fieldInput('custAddrCountryCd')).toHaveValue(ACCOUNT.custAddrCountryCd);
    expect(fieldInput('custAddrZip')).toHaveValue(ACCOUNT.custAddrZip);
    // Each phone number is three fields around the mapset's own punctuation.
    expectPhoneSegments(['acsPh1A', 'acsPh1B', 'acsPh1C'], ACCOUNT.custPhoneNum1);
    expectPhoneSegments(['acsPh2A', 'acsPh2B', 'acsPh2C'], ACCOUNT.custPhoneNum2);
    expect(fieldInput('custGovtIssuedId')).toHaveValue(ACCOUNT.custGovtIssuedId);
    expect(fieldInput('custEftAccountId')).toHaveValue(ACCOUNT.custEftAccountId);
    expect(fieldInput('custPriCardHolderInd')).toHaveValue(
      ACCOUNT.custPriCardHolderInd,
    );
  });

  it('renders each date as its three mapset segments in YYYY-MM-DD order', async () => {
    await renderScreen();

    expectDateSegments(['opnYear', 'opnMon', 'opnDay'], '2020-01-15');
    expectDateSegments(['dobYear', 'dobMon', 'dobDay'], '1980-04-30');
    // The later segments carry their own accessible names, because the mapset
    // captions only the first of the three.
    expect(fieldInput('opnMon')).toHaveAccessibleName('Open Date month');
    expect(fieldInput('opnDay')).toHaveAccessibleName('Open Date day');
  });

  it('limits each entry field to its declared BMS width', async () => {
    await renderScreen();

    expect(fieldInput('acctsid')).toHaveAttribute('maxLength', '11');
    expect(fieldInput('acctActiveStatus')).toHaveAttribute('maxLength', '1');
    expect(fieldInput('opnYear')).toHaveAttribute('maxLength', '4');
    expect(fieldInput('opnMon')).toHaveAttribute('maxLength', '2');
    expect(fieldInput('opnDay')).toHaveAttribute('maxLength', '2');
    expect(fieldInput('actSsn1')).toHaveAttribute('maxLength', '3');
    expect(fieldInput('actSsn2')).toHaveAttribute('maxLength', '2');
    expect(fieldInput('actSsn3')).toHaveAttribute('maxLength', '4');
    expect(fieldInput('acctCreditLimit')).toHaveAttribute('maxLength', '15');
    expect(fieldInput('custAddrStateCd')).toHaveAttribute('maxLength', '2');
    expect(fieldInput('custAddrZip')).toHaveAttribute('maxLength', '5');
    expect(fieldInput('custPriCardHolderInd')).toHaveAttribute('maxLength', '1');
  });
});

/* ------------------------------------------------------------------ */
/* Field validation (COACTUPC 1200-EDIT-MAP-INPUTS)                   */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — field validation', () => {
  it('reports a blank required field as "must be supplied." and does not submit', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custFirstName', '');
    await commit(user, true);

    expect(screen.getByRole('alert')).toHaveTextContent('First Name must be supplied.');
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('reports a non-numeric field as "must be all numeric." and does not submit', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custAddrZip', 'ABCDE');
    await commit(user, true);

    expect(screen.getByRole('alert')).toHaveTextContent('Zip must be all numeric.');
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('reports an out-of-domain flag as "must be Y or N." and does not submit', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'acctActiveStatus', 'X');
    await commit(user, true);

    expect(screen.getByRole('alert')).toHaveTextContent('Account Status must be Y or N.');
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('rejects a non-numeric account key with the account-number message', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'acctsid', 'ABCDEFGHIJK');
    await commit(user, true);

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Account Number if supplied must be a 11 digit Non-Zero Number',
    );
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('highlights the failing field locally, marking a blank required field', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custFirstName', '');
    await commit(user, true);

    // Highlighting is page-local: the field itself carries the invalid state and
    // the blank marker; the shared shell renders only the single message line.
    expect(fieldInput('custFirstName')).toBeInvalid();
    expect(fieldInput('custFirstName')).toHaveClass('field', 'fieldError');
    expect(fieldInput('custLastName')).not.toBeInvalid();
    // The marker column is RESERVED on every field, so a failed edit moves nothing:
    // every field still carries its column and exactly one of them holds the `*`.
    const markers = document.querySelectorAll(
      'span.accountUpdate__marker[aria-hidden="true"]',
    );
    expect(markers.length).toBeGreaterThan(1);
    const marked = Array.from(markers).filter(
      (marker) => (marker.textContent ?? '') !== '',
    );
    expect(marked).toHaveLength(1);
    expect(marked[0]).toHaveTextContent('*');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('clears the highlight once the corrected screen validates', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custAddrZip', 'ABCDE');
    await commit(user, true);
    expect(fieldInput('custAddrZip')).toBeInvalid();

    // A corrected value that also differs from the record read, so the screen has
    // a change to validate and then rewrite.
    await retype(user, 'custAddrZip', '98052');
    await commit(user, true);

    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });
    expect(fieldInput('custAddrZip')).not.toBeInvalid();
    // The reserved marker columns are still there; none of them carries the `*`.
    expect(markedMarkerCount()).toBe(0);
  });
});

/* ------------------------------------------------------------------ */
/* Optimistic-lock conflict (COACTUPC DATA-WAS-CHANGED-BEFORE-UPDATE) */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — optimistic-lock conflict', () => {
  it('displays the verbatim conflict message on an HTTP 409', async () => {
    updateAccountMock.mockRejectedValue(
      new ApiError(409, OPTIMISTIC_LOCK_MESSAGE),
    );
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe('Record changed by some one else. Please review');
    expect(banner.textContent).toBe(OPTIMISTIC_LOCK_MESSAGE);
    // COACTUP.bms declares INFOMSG (L480) as well as ERRMSG (L489) and COACTUPC
    // populates both on this outcome. The concurrency branch of 2000-DECIDE-ACTION
    // (L2611-2612) sets ACUP-SHOW-DETAILS — not a failure state — and 3250-SETUP-INFOMSG
    // (L2962-2963) maps SHOW-DETAILS to PROMPT-FOR-CHANGES. INFORM-FAILURE belongs to
    // ACUP-CHANGES-OKAYED-LOCK-ERROR and ACUP-CHANGES-OKAYED-BUT-FAILED only (L2971-2974),
    // so the record is presented for review rather than declared a failure.
    expect(infoBanner()?.textContent).toBe('Update account details presented above.');
  });

  it('puts the record on display so the invited review has something to review', async () => {
    // 2000-DECIDE-ACTION L2611-2612 answers the concurrency branch with ACUP-SHOW-DETAILS,
    // and 3200-SETUP-SCREEN-VARS L2715-2717 paints that state through
    // 3202-SHOW-ORIGINAL-VALUES — from ACUP-OLD-DETAILS, never from the edited
    // ACUP-NEW-DETAILS. So the map that follows a conflict carries the record, and the
    // read that produced it is the one PFK12 performs.
    const WINNER = { ...ACCOUNT, acctCreditLimit: '4242.00', version: ACCOUNT.version + 5 };
    getAccountMock.mockResolvedValueOnce(ACCOUNT).mockResolvedValue(WINNER);
    updateAccountMock.mockRejectedValueOnce(new ApiError(409, OPTIMISTIC_LOCK_MESSAGE));
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);
    await screen.findByRole('alert');
    expect(submittedRequest(0).version).toBe(LOADED_VERSION);

    // The winner's value is what the operator now sees, in place of the keystrokes that
    // lost. Reviewing the change that beat them is the only way to re-apply an edit
    // without silently undoing it.
    await waitFor(() => {
      expect(fieldInput('acctCreditLimit')).toHaveValue('4242.00');
    });
    expect(getAccountMock).toHaveBeenCalledTimes(2);

    // The record on display and the snapshot are one set, so ENTER alone finds no change.
    await user.keyboard('{Enter}');
    expect((await screen.findByRole('alert')).textContent).toBe(
      'No change detected with respect to values fetched.',
    );
    expect(updateAccountMock).toHaveBeenCalledTimes(1);

    // Re-applying the edit on top of the reviewed record retries against the live version.
    updateAccountMock.mockResolvedValue(COMMITTED_ACCOUNT);
    await commit(user);

    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(2);
    });
    expect(submittedRequest(1).version).toBe(WINNER.version);
    expect(submittedRequest(1).acctCreditLimit).toBe(EDITED_CREDIT_LIMIT);
  });

  it('surfaces the frozen conflict message even when the body carries another text', async () => {
    updateAccountMock.mockRejectedValue(
      new ApiError(409, 'Optimistic lock failure on Account', {
        timestamp: '2026-08-05T10:15:30Z',
        status: 409,
        error: 'Conflict',
        message: 'Optimistic lock failure on Account',
        path: `/accounts/${ACCOUNT_ID}`,
      }),
    );
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe(OPTIMISTIC_LOCK_MESSAGE);
  });

  it('leaves the stored record untouched and never retries the version that lost', async () => {
    updateAccountMock.mockRejectedValueOnce(
      new ApiError(409, OPTIMISTIC_LOCK_MESSAGE),
    );
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'acctCreditLimit', '9000.00');
    await commit(user, true);
    await screen.findByRole('alert');

    // The refused write carried the snapshot read at display time, and nothing was stored.
    expect(submittedRequest(0).version).toBe(LOADED_VERSION);
    // The reviewed record replaces the refused entry, so 9000.00 is gone from the screen
    // and a second F5 cannot re-send the version that already lost.
    await waitFor(() => {
      expect(fieldInput('acctCreditLimit')).toHaveValue(ACCOUNT.acctCreditLimit);
    });

    await commit(user, true);
    expect(updateAccountMock).toHaveBeenCalledTimes(1);
    expect((await screen.findByRole('alert')).textContent).toBe(
      'No change detected with respect to values fetched.',
    );
  });
});

/* ------------------------------------------------------------------ */
/* Committed update                                                   */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — committed update', () => {
  it('reports the commit and leaves the error region empty', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    const banner = await findInfoBanner();
    expect(banner.textContent).toBe(UPDATE_SUCCESS_MESSAGE);
    expect(screen.queryByRole('alert')).toBeNull();
    // The reserved marker columns are still there; none of them carries the `*`.
    expect(markedMarkerCount()).toBe(0);
  });

  it('refreshes the entry fields from the record the update returns', async () => {
    updateAccountMock.mockResolvedValue({
      ...COMMITTED_ACCOUNT,
      acctCurrBal: '1250.75',
    });
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    await waitFor(() => {
      expect(fieldInput('acctCurrBal')).toHaveValue('1250.75');
    });
  });
});

/* ------------------------------------------------------------------ */
/* In-flight duplicate-save guard                                     */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — in-flight duplicate-save guard', () => {
  it('closes the entry fields while the save is in flight and writes once for a repeated F5', async () => {
    let acknowledge!: (value: AccountUpdateResponseDto) => void;
    updateAccountMock.mockReturnValueOnce(
      new Promise<AccountUpdateResponseDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    // ``ATTRB=ASKIP`` for the whole in-flight interval: the record cannot be re-edited
    // while the optimistic-locked PUT is outstanding.
    expect(fieldInput('acctCreditLimit')).toBeDisabled();
    expect(fieldInput('acctActiveStatus')).toBeDisabled();

    // A second F5 must not re-issue the write against a stale ``version``.
    await user.keyboard('{F5}');
    expect(updateAccountMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge(COMMITTED_ACCOUNT);
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(updateAccountMock).toHaveBeenCalledTimes(1);
    expect(fieldInput('acctCreditLimit')).toBeEnabled();
  });
});

/* ------------------------------------------------------------------ */
/* Unauthorized (HTTP 401)                                            */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — unauthorized', () => {
  it('surfaces a 401 read failure on the message line and displays no record', async () => {
    getAccountMock.mockRejectedValue(new ApiError(401, 'Unauthorized'));
    await renderScreen();

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('Unauthorized');
    expect(fieldInput('acctActiveStatus')).toHaveValue('');
  });

  it('surfaces a 401 update failure without treating it as a lock conflict', async () => {
    updateAccountMock.mockRejectedValue(new ApiError(401, 'Unauthorized'));
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe('Unauthorized');
    expect(banner.textContent).not.toBe(OPTIMISTIC_LOCK_MESSAGE);
    expect(updateAccountMock).toHaveBeenCalledTimes(1);
  });
});

/* ------------------------------------------------------------------ */
/* Function keys (BMS line-24 legend)                                 */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — function keys', () => {
  it('publishes ENTER=Process, F3=Exit, F5=Save and F12=Cancel in legend order', async () => {
    await renderScreen();

    const toolbar = screen.getByRole('group', { name: 'Function keys' });
    // ``3390-SETUP-INFOMSG-ATTRS`` un-darkens FKEY05 only while the confirmation is
    // being prompted and FKEY12 only once unsaved changes exist, so a freshly
    // displayed screen legends two entries.
    expect(
      within(toolbar)
        .getAllByRole('button')
        .map((key) => key.textContent),
    ).toEqual(['ENTER=Process', 'F3=Exit']);

    const user = userEvent.setup();
    await retype(user, 'acctCreditLimit', '9999.99');
    await user.keyboard('{Enter}');

    // ENTER runs the program's own edit pass, which is a round trip: the legend gains
    // F5/F12 only once the service has confirmed the submission passes every edit.
    await waitFor(() => {
      expect(
        within(toolbar)
          .getAllByRole('button')
          .map((key) => key.textContent),
      ).toEqual(['ENTER=Process', 'F3=Exit', 'F5=Save', 'F12=Cancel']);
    });
  });

  it('validates the screen on ENTER and invites the save', async () => {
    await renderScreen();
    const user = userEvent.setup();

    // ``1200-EDIT-MAP-INPUTS`` runs against the CHANGES on the map, so a screen
    // with nothing altered is reported as such instead of inviting a rewrite.
    await retype(user, 'acctCreditLimit', '7500.00');
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CONFIRMATION_MESSAGE);
    });
    // The edit pass ran against the entered values, and it rewrote nothing.
    expect(validateAccountUpdateMock).toHaveBeenCalledTimes(1);
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('reports an unchanged screen on ENTER instead of inviting the save', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await user.keyboard('{Enter}');

    expect(screen.getByRole('alert')).toHaveTextContent(
      'No change detected with respect to values fetched.',
    );
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('stacks the INFOMSG line above the ERRMSG line, in the rows COACTUP declares', async () => {
    // 3250-SETUP-INFOMSG (L2979-2981) moves WS-INFO-MSG to INFOMSGO and then
    // WS-RETURN-MSG to ERRMSGO, so an unchanged ENTER sends both fields on one send.
    // COACTUP.bms declares INFOMSG ATTRB=(ASKIP) COLOR=NEUTRAL LENGTH=45 POS=(22,23) and
    // ERRMSG ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78 POS=(23,1): the informational
    // line sits in the row ABOVE the error, and it is NEUTRAL rather than green -- the
    // program never moves DFHGREEN anywhere.
    await renderScreen();
    const user = userEvent.setup();

    await user.keyboard('{Enter}');
    await screen.findByRole('alert');

    const rows = Array.from(document.querySelectorAll('.errorBanner'));
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveClass('errorBanner--infoField');
    expect(rows[0]).toHaveTextContent(PROMPT_FOR_CHANGES_MESSAGE);
    expect(rows[1]).toHaveTextContent('No change detected with respect to values fetched.');
    expect(rows[1]).toHaveAttribute('role', 'alert');
    // The row above the error is never the green ERRMSG variant.
    expect(rows[1]).not.toHaveClass('errorBanner--infoField');
  });

  it('reports a failing edit on ENTER without submitting', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custAddrCountryCd', '');
    await user.keyboard('{Enter}');

    expect(screen.getByRole('alert')).toHaveTextContent('Country must be supplied.');
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('commits on F5', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);

    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });
    expect(submittedRequest(0).version).toBe(LOADED_VERSION);
    expect(await findInfoBanner()).toHaveTextContent(UPDATE_SUCCESS_MESSAGE);
  });

  it('exits to the calling menu on F3', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await user.keyboard('{F3}');

    expect(screen.getByTestId('menu-route')).toBeInTheDocument();
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('abandons the edits by re-reading the record on F12', async () => {
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'acctCreditLimit', '9999.99');
    await user.keyboard('{F12}');

    // The screen stays on COACTUP and repaints from a fresh read, so the edit is
    // discarded and nothing is written.
    await waitFor(() => {
      expect(fieldInput('acctCreditLimit')).toHaveValue(ACCOUNT.acctCreditLimit);
    });
    expect(getAccountMock).toHaveBeenCalledTimes(2);
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('activates the same handlers from the legend buttons', async () => {
    await renderScreen();
    const user = userEvent.setup();
    const toolbar = screen.getByRole('group', { name: 'Function keys' });

    await retype(user, 'acctCreditLimit', '7500.00');
    await user.click(within(toolbar).getByRole('button', { name: 'ENTER=Process' }));
    await waitFor(() => {
      expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CONFIRMATION_MESSAGE);
    });

    await user.click(within(toolbar).getByRole('button', { name: 'F5=Save' }));
    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });

    await user.click(within(toolbar).getByRole('button', { name: 'F3=Exit' }));
    expect(screen.getByTestId('menu-route')).toBeInTheDocument();
  });
});

describe('AccountUpdatePage — the ENTER edit pass is the program\'s own', () => {
  it('reports a semantic edit the service performs, and does not claim validation', async () => {
    // The FICO range, the state-code and area-code lookups and the state/zip combination
    // are edited by the service against the one copy of those lookup tables. ENTER must
    // therefore reach them before it publishes PROMPT-FOR-CONFIRMATION, or it would be
    // inviting a save the rewrite is about to refuse.
    validateAccountUpdateMock.mockRejectedValue(
      new ApiError(400, 'FICO Score: should be between 300 and 850', {
        timestamp: '2026-01-15T10:20:30.123456',
        status: 400,
        error: 'Bad Request',
        message: 'FICO Score: should be between 300 and 850',
        path: `/accounts/${ACCOUNT_ID}/validate`,
        errorCode: 'VALIDATION_FAILED',
        fieldErrors: { custFicoCreditScore: 'FICO Score: should be between 300 and 850' },
      }),
    );
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custFicoCreditScore', '3000');
    await user.keyboard('{Enter}');

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe('FICO Score: should be between 300 and 850');
    // The confirmation is NOT published, so the operator is never told to press F5.
    expect(infoBanner()?.textContent).toBe('Update account details presented above.');
    expect(updateAccountMock).not.toHaveBeenCalled();
  });

  it('marks the field the service named and moves the cursor to it', async () => {
    validateAccountUpdateMock.mockRejectedValue(
      new ApiError(400, 'FICO Score: should be between 300 and 850', {
        timestamp: '2026-01-15T10:20:30.123456',
        status: 400,
        error: 'Bad Request',
        message: 'FICO Score: should be between 300 and 850',
        path: `/accounts/${ACCOUNT_ID}/validate`,
        errorCode: 'VALIDATION_FAILED',
        fieldErrors: { custFicoCreditScore: 'FICO Score: should be between 300 and 850' },
      }),
    );
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'custFicoCreditScore', '3000');
    await user.keyboard('{Enter}');

    await screen.findByRole('alert');
    // The client cannot know which of 43 fields a semantic edit refused; the envelope's
    // fieldErrors names it, which is what makes the marker and the cursor possible.
    expect(fieldInput('custFicoCreditScore')).toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => {
      expect(fieldInput('custFicoCreditScore')).toHaveFocus();
    });
  });

  it('edits the entered shape before spending a round trip on it', async () => {
    await renderScreen();
    const user = userEvent.setup();

    // A malformed entry is refused by the shape pass, so no request is issued at all.
    await retype(user, 'acctActiveStatus', 'X');
    await user.keyboard('{Enter}');

    // 1220-EDIT-YESNO composes its message from WS-EDIT-VARIABLE-NAME, which
    // COACTUPC L1472-1475 sets to 'Account Status' for this field.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Account Status must be Y or N.',
    );
    expect(validateAccountUpdateMock).not.toHaveBeenCalled();
    expect(updateAccountMock).not.toHaveBeenCalled();
  });
});

describe('AccountUpdatePage — zip field width (ACSZIPC LENGTH=5)', () => {
  it('seeds the zip with the five characters the map field holds', async () => {
    // COACTUP.bms L382-385 declares ACSZIPC LENGTH=5 and COACTUP.CPY L572 declares
    // ACSZIPCO PIC X(5), while CUST-ADDR-ZIP is PIC X(10). COACTUPC L2843 MOVEs the
    // record field into the map field, which truncates on the right, so a stored ZIP+4
    // reaches the screen as its five-digit prefix rather than as ten characters in a box
    // that can only ever show five.
    getAccountMock.mockResolvedValue({ ...ACCOUNT, custAddrZip: '46713-5148' });
    await renderScreen();

    const zip = fieldInput('custAddrZip');
    expect(zip).toHaveValue('46713');
    expect(zip).toHaveAttribute('maxLength', '5');
    // Nothing is hidden: the value is no longer than the field that holds it.
    expect(zip.value.length).toBeLessThanOrEqual(5);
  });

  it('registers a stored ZIP+4 as a change, so the truncation is announced', async () => {
    // 9500-STORE-FETCHED-DATA fills ACUP-OLD-CUST-ADDR-ZIP from the record (L3875), and
    // 1205-COMPARE-OLD-NEW L1744-1747 compares TRIM of the map's five characters against
    // TRIM of the record's ten. They differ, so an untouched ZIP+4 account is not
    // "unchanged": the rewrite would store the five (L4025), and the operator is told
    // there is something to save before F5 does it.
    getAccountMock.mockResolvedValue({ ...ACCOUNT, custAddrZip: '46713-5148' });
    await renderScreen();
    const user = userEvent.setup();

    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(validateAccountUpdateMock).toHaveBeenCalledTimes(1);
    });
    expect(validateAccountUpdateMock.mock.calls[0][1].custAddrZip).toBe('46713');
    expect((await findInfoBanner()).textContent).toBe('Changes validated.Press F5 to save');
  });
});

describe('AccountUpdatePage — keyboard lock while the rewrite is outstanding', () => {
  it('holds every function key until the write reports back', async () => {
    let commitWrite!: (value: AccountUpdateResponseDto) => void;
    updateAccountMock.mockReturnValueOnce(
      new Promise<AccountUpdateResponseDto>((resolve) => {
        commitWrite = resolve;
      }),
    );
    await renderScreen();
    const user = userEvent.setup();

    await commit(user);
    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });

    // A 3270 locked the keyboard from the AID until the reply, so the screen cannot be
    // left while its write is in flight — which is what made a write that commits with
    // nobody left to see the confirmation unreachable.
    await user.keyboard('{F3}');
    expect(screen.queryByTestId('menu-route')).toBeNull();

    commitWrite(COMMITTED_ACCOUNT);
    await waitFor(() => {
      expect(infoBanner()?.textContent).toBe('Changes committed to database');
    });

    // Once the reply is in, the keys act again.
    await user.keyboard('{F3}');
    expect(screen.getByTestId('menu-route')).toBeInTheDocument();
  });
});
