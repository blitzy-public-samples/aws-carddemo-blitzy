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
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  getAccount: getAccountMock,
  updateAccount: updateAccountMock,
  signon: signonMock,
  ApiError,
}));

/* ------------------------------------------------------------------ */
/* Modules loaded after the mock is registered                         */
/* ------------------------------------------------------------------ */

type AccountUpdatePageComponent = (typeof import('./AccountUpdatePage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SetSession = (typeof import('../hooks/useSession'))['__setSession'];

let AccountUpdatePage: AccountUpdatePageComponent;
let Layout: LayoutComponent;
let setSession: SetSession;

beforeAll(async () => {
  // Imported here — not statically — so the page, ``useApi`` and ``useSession``
  // all bind to the mocked ``../api``. No module reset, so they share the single
  // React instance used by the statically imported Testing Library.
  ({ default: AccountUpdatePage } = await import('./AccountUpdatePage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ __setSession: setSession } = await import('../hooks/useSession'));
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
  act(() => {
    setSession(SESSION_USER, 'U');
  });
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
          <Route path="/signon" element={<div data-testid="signon-route" />} />
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
  signonMock.mockReset();
  getAccountMock.mockResolvedValue(ACCOUNT);
  updateAccountMock.mockResolvedValue(COMMITTED_ACCOUNT);
});

afterEach(() => {
  // Return the shared session store to signed-out so no state leaks across tests.
  act(() => {
    setSession(null, null);
  });
});

/* ------------------------------------------------------------------ */
/* Load and optimistic-lock version snapshot                          */
/* ------------------------------------------------------------------ */

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
      'Account number must be a non zero 11 digit number',
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
      'Account number must be a non zero 11 digit number',
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
      screen.getByRole('heading', { name: 'Update Account', level: 2 }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Customer Details', level: 3 }),
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
      'Account number must be a non zero 11 digit number',
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
    const markers = document.querySelectorAll('span.fieldError[aria-hidden="true"]');
    expect(markers).toHaveLength(1);
    expect(markers[0]).toHaveTextContent('*');
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
    expect(document.querySelectorAll('span.fieldError[aria-hidden="true"]')).toHaveLength(
      0,
    );
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
    expect(infoBanner()).toBeNull();
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

  it('keeps the entered values and the version snapshot so the user can review', async () => {
    updateAccountMock.mockRejectedValueOnce(
      new ApiError(409, OPTIMISTIC_LOCK_MESSAGE),
    );
    await renderScreen();
    const user = userEvent.setup();

    await retype(user, 'acctCreditLimit', '9000.00');
    await commit(user, true);
    await screen.findByRole('alert');

    // Nothing was overwritten: the edit survives and the same snapshot is retried.
    expect(fieldInput('acctCreditLimit')).toHaveValue('9000.00');
    expect(submittedRequest(0).version).toBe(LOADED_VERSION);

    await commit(user, true);
    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(2);
    });
    expect(submittedRequest(1).version).toBe(LOADED_VERSION);
    expect(submittedRequest(1).acctCreditLimit).toBe('9000.00');
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
    expect(document.querySelectorAll('span.fieldError[aria-hidden="true"]')).toHaveLength(
      0,
    );
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
/* Unauthorized (HTTP 401)                                            */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — unauthorized', () => {
  it('surfaces a 401 read failure on the message line without redirecting', async () => {
    getAccountMock.mockRejectedValue(new ApiError(401, 'Unauthorized'));
    await renderScreen();

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('Unauthorized');
    // The 401 -> /signon redirect belongs to the api client's response
    // interceptor, which the mocked module bypasses; the page never navigates.
    expect(screen.queryByTestId('signon-route')).toBeNull();
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
    expect(screen.queryByTestId('signon-route')).toBeNull();
  });
});

/* ------------------------------------------------------------------ */
/* Function keys (BMS line-24 legend)                                 */
/* ------------------------------------------------------------------ */

describe('AccountUpdatePage — function keys', () => {
  it('publishes ENTER=Process, F3=Exit, F5=Save and F12=Cancel in legend order', async () => {
    await renderScreen();

    const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });
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

    expect(
      within(toolbar)
        .getAllByRole('button')
        .map((key) => key.textContent),
    ).toEqual(['ENTER=Process', 'F3=Exit', 'F5=Save', 'F12=Cancel']);
  });

  it('validates the screen on ENTER and invites the save', async () => {
    await renderScreen();
    const user = userEvent.setup();

    // ``1200-EDIT-MAP-INPUTS`` runs against the CHANGES on the map, so a screen
    // with nothing altered is reported as such instead of inviting a rewrite.
    await retype(user, 'acctCreditLimit', '7500.00');
    await user.keyboard('{Enter}');

    expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CONFIRMATION_MESSAGE);
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
    const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });

    await retype(user, 'acctCreditLimit', '7500.00');
    await user.click(within(toolbar).getByRole('button', { name: 'ENTER=Process' }));
    expect(infoBanner()).toHaveTextContent(PROMPT_FOR_CONFIRMATION_MESSAGE);

    await user.click(within(toolbar).getByRole('button', { name: 'F5=Save' }));
    await waitFor(() => {
      expect(updateAccountMock).toHaveBeenCalledTimes(1);
    });

    await user.click(within(toolbar).getByRole('button', { name: 'F3=Exit' }));
    expect(screen.getByTestId('menu-route')).toBeInTheDocument();
  });
});
