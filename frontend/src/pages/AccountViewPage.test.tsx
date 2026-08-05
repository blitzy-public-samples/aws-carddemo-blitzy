/**
 * AccountViewPage.test
 * ====================
 *
 * :purpose: Verify the read-only account-view workflow — BMS mapset
 *     ``app/bms/COACTVW.bms`` with symbolic map ``app/cpy-bms/COACTVW.CPY``, CICS
 *     transaction ``CAVW``, program ``COACTVWC`` — as re-expressed by
 *     :func:`AccountViewPage`: the fetch driven by the ``/accounts/:accountId``
 *     route, the verbatim BMS captions and their column padding, the preserved
 *     ``acctExpiraionDate`` misspelling, the non-editable detail cells, the three
 *     masked regulated identifiers, the ``Account number must be a non zero 11 digit
 *     number`` filter edit, and the single ``F3=Exit`` function key.
 * :output: Jest assertions only; the suite writes no files and performs no I/O.
 * :note: ``../api`` is doubled with ``jest.unstable_mockModule``, and the modules
 *     under test are imported dynamically once that mock is registered, as the
 *     suite runs on Jest's native-ESM runtime. The double must publish
 *     ``getAccount`` (the page), ``ApiError`` (``useApi`` narrows a rejection with
 *     ``instanceof``) and ``signon`` (``useSession`` binds it at import time); no
 *     axios instance, network call, or ``import.meta`` evaluation occurs.
 * :note: The page is rendered inside the shared ``Layout`` shell: the line-23
 *     message region and the line-24 function-key bar are published through
 *     ``useScreenChrome`` and rendered by that shell, not by the page body.
 */

import { jest } from '@jest/globals';
import {
  fireEvent,
  getDefaultNormalizer,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { AccountViewResponseDto, ApiErrorResponse } from '../types';

/** Account number exercised by the suite (``ACCT-ID`` 9(11), zero padded). */
const ACCOUNT_ID = '00000000011';

/**
 * Line-23 filter-edit message of ``COACTVWC`` (``SEARCHED-ACCT-ZEROES`` and
 * ``SEARCHED-ACCT-NOT-NUMERIC`` share one text).
 */
const ACCOUNT_NUMBER_ERROR = 'Account number must be a non zero 11 digit number';

/** Line-23 message of ``COACTVWC`` ``DID-NOT-FIND-ACCT-IN-ACCTDAT``. */
const ACCOUNT_NOT_FOUND_ERROR = 'Did not find this account in account master file';

/**
 * The three regulated customer identifiers as the SERVICE stores them. They are
 * declared so the suite can assert that none of them reaches the document: the
 * account service masks all three in ``AccountMapper`` before the response leaves the
 * boundary (AAP 0.6.7), so a raw value in the DOM would mean that mask was lost.
 */
const RAW_SSN = '123456789';
const RAW_GOVT_ISSUED_ID = 'IL-DL-987654321';
const RAW_EFT_ACCOUNT_ID = '0000000042';

/**
 * The masked renderings ``GET /accounts/{id}`` actually returns — ``PiiMasker.maskSsn``
 * for the Social Security number and ``PiiMasker.maskIdentifier`` for the other two,
 * each retaining only the trailing four characters. The fixture below carries these,
 * not the raw values, so the assertions bind to the wire contract the service
 * publishes.
 */
const MASKED_SSN = '***-**-6789';
const MASKED_GOVT_ISSUED_ID = '***********4321';
const MASKED_EFT_ACCOUNT_ID = '******0042';

/**
 * Text-matcher normalizer that keeps a caption's BMS column padding intact, so an
 * assertion fails when internal spacing changes.
 */
const EXACT_TEXT = getDefaultNormalizer({ trim: false, collapseWhitespace: false });

/** ``GET /accounts/{id}`` stand-in bound by the page through ``useApi``. */
const getAccountMock =
  jest.fn<(accountId: string) => Promise<AccountViewResponseDto>>();

/** ``POST /auth/signon`` stand-in; ``useSession`` binds it when the barrel loads. */
const signonMock = jest.fn();

/**
 * Stand-in for the ``../api`` ``ApiError`` carrying the same constructor shape, so
 * ``useApi`` narrows a rejection with ``instanceof`` and reads
 * ``isOptimisticLockConflict``.
 *
 * :param status: HTTP status code, or ``0`` for a transport failure.
 * :param message: resolved, human-readable message.
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

jest.unstable_mockModule('../api', () => ({
  // The session store and the REST hook this screen's module graph loads bind to
  // these barrel exports as well. ``getSessionIdentity`` is the production
  // ``GET /session`` probe the session harness drives; unanswered by this suite it
  // reports no session, and the harness is the only thing that changes that.
  getSessionIdentity: jest.fn(() => Promise.reject(new Error('No session'))),
  logout: jest.fn(() => Promise.resolve(undefined)),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  getAccount: getAccountMock,
  signon: signonMock,
  ApiError,
}));

/**
 * ``GET /accounts/00000000011`` payload. Monetary values and identifiers stay
 * ``string`` so preserved scale and zero-padded width are observable, and every
 * date differs so a mis-bound cell cannot pass.
 */
const account: AccountViewResponseDto = {
  acctId: ACCOUNT_ID,
  acctActiveStatus: 'Y',
  acctCurrBal: '1234.56',
  acctCreditLimit: '5000.00',
  acctCashCreditLimit: '10000.00',
  acctOpenDate: '2020-01-15',
  acctExpiraionDate: '2027-12-31',
  acctReissueDate: '2024-06-30',
  acctCurrCycCredit: '0.00',
  acctCurrCycDebit: '375.25',
  acctGroupId: 'ZEROAPR',
  custId: '000000011',
  custFirstName: 'JOHN',
  custMiddleName: 'Q',
  custLastName: 'PUBLIC',
  custAddrLine1: '123 MAIN STREET',
  custAddrLine2: 'APT 4B',
  custAddrLine3: 'SPRINGFIELD',
  custAddrStateCd: 'IL',
  custAddrCountryCd: 'USA',
  custAddrZip: '62704',
  custPhoneNum1: '(217)555-0100',
  custPhoneNum2: '(217)555-0199',
  custSsn: MASKED_SSN,
  custGovtIssuedId: MASKED_GOVT_ISSUED_ID,
  custDobYyyyMmDd: '1985-03-27',
  custEftAccountId: MASKED_EFT_ACCOUNT_ID,
  custPriCardHolderInd: 'Y',
  custFicoCreditScore: '750',
  version: 3,
};

/**
 * Every detail cell of the mapset paired with the exact text it must carry, which
 * locks the field-to-cell binding of the 28 ``COACTVW`` display fields (the
 * twenty-ninth, ``ACCTSID``, is the key input asserted separately).
 */
const DETAIL_CELLS: ReadonlyArray<readonly [string, string]> = [
  ['acct-active-status', account.acctActiveStatus],
  ['acct-open-date', account.acctOpenDate],
  ['acct-credit-limit', account.acctCreditLimit],
  ['acct-expiraion-date', account.acctExpiraionDate],
  ['acct-cash-credit-limit', account.acctCashCreditLimit],
  ['acct-reissue-date', account.acctReissueDate],
  ['acct-curr-bal', account.acctCurrBal],
  ['acct-curr-cyc-credit', account.acctCurrCycCredit],
  ['acct-group-id', account.acctGroupId],
  ['acct-curr-cyc-debit', account.acctCurrCycDebit],
  ['cust-id', account.custId],
  ['cust-ssn', MASKED_SSN],
  ['cust-dob-yyyy-mm-dd', account.custDobYyyyMmDd],
  ['cust-fico-credit-score', String(account.custFicoCreditScore)],
  ['cust-first-name', account.custFirstName],
  ['cust-middle-name', account.custMiddleName],
  ['cust-last-name', account.custLastName],
  ['cust-addr-line-1', account.custAddrLine1],
  ['cust-addr-state-cd', account.custAddrStateCd],
  ['cust-addr-line-2', account.custAddrLine2],
  ['cust-addr-zip', account.custAddrZip],
  ['cust-addr-line-3', account.custAddrLine3],
  ['cust-addr-country-cd', account.custAddrCountryCd],
  ['cust-phone-num-1', account.custPhoneNum1],
  ['cust-govt-issued-id', account.custGovtIssuedId],
  ['cust-phone-num-2', account.custPhoneNum2],
  ['cust-eft-account-id', account.custEftAccountId],
  ['cust-pri-card-holder-ind', account.custPriCardHolderInd],
];

/** Verbatim TURQUOISE captions of the account block of ``COACTVW.bms``. */
const ACCOUNT_CAPTIONS: readonly string[] = [
  'Account Number :',
  'Active Y/N:',
  'Opened:',
  'Credit Limit        :',
  'Expiry:',
  'Cash credit Limit   :',
  'Reissue:',
  'Current Balance     :',
  'Current Cycle Credit:',
  'Account Group:',
  'Current Cycle Debit :',
];

/** Verbatim TURQUOISE captions of the customer block of ``COACTVW.bms``. */
const CUSTOMER_CAPTIONS: readonly string[] = [
  'Customer id  :',
  'SSN:',
  'Date of birth:',
  'FICO Score:',
  'First Name',
  'Middle Name:',
  'Last Name :',
  'Address:',
  'State',
  'Zip',
  'City',
  'Country',
  'Phone 1:',
  'Government Issued Id Ref    :',
  'Phone 2:',
  'EFT Account Id:',
  'Primary Card Holder Y/N:',
];

type AccountViewPageComponent = (typeof import('./AccountViewPage'))['default'];
type LayoutComponent = (typeof import('../components/Layout'))['default'];
type SessionHarness = typeof import('../testing/sessionHarness');

let AccountViewPage: AccountViewPageComponent;
let Layout: LayoutComponent;
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so the page, the shell, and the hooks
  // all bind to the mocked ``../api``; no module reset, so React stays shared
  // with Testing Library.
  ({ default: AccountViewPage } = await import('./AccountViewPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
});

beforeEach(async () => {
  getAccountMock.mockReset();
  getAccountMock.mockResolvedValue(account);
  signonMock.mockReset();
  await seedSignedOnSession('USER0001', 'U');
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * Render the screen at a router entry so ``useParams`` resolves the ``accountId``
 * path segment, inside the shell that renders the published message region and
 * function-key bar.
 *
 * :param path: initial router entry, with or without the account-number segment.
 * :returns: the ``main`` body region holding the rendered screen.
 */
function renderAt(path: string): HTMLElement {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Layout>
        <Routes>
          <Route path="/accounts" element={<AccountViewPage />} />
          <Route path="/accounts/:accountId" element={<AccountViewPage />} />
          <Route path="/menu" element={<div data-testid="menu-landing">MENU</div>} />
        </Routes>
      </Layout>
    </MemoryRouter>,
  );
  return screen.getByRole('main');
}

/**
 * Render at ``/accounts/00000000011`` and wait until the fetched record reaches the
 * screen.
 *
 * :returns: the ``main`` body region holding the rendered screen.
 */
async function renderLoaded(): Promise<HTMLElement> {
  const main = renderAt(`/accounts/${ACCOUNT_ID}`);
  await waitFor(() => {
    expect(screen.getByTestId('cust-id').textContent).toBe(account.custId);
  });
  return main;
}

/**
 * The account-number key field (BMS ``ACCTSID``).
 *
 * :returns: the search input element.
 */
function acctInput(): HTMLElement {
  return screen.getByTestId('acctsid');
}

/**
 * Type an account number into the key field and submit it with the ENTER AID.
 *
 * :param value: the account number exactly as the operator would key it.
 */
function submitAccountNumber(value: string): void {
  const input = acctInput();
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key: 'Enter' });
}

describe('AccountViewPage — fetch driven by the route (CAVW / COACTVWC)', () => {
  it('fetches the account with the 11-digit route account number, as a string', async () => {
    await renderLoaded();

    expect(getAccountMock).toHaveBeenCalledTimes(1);
    expect(getAccountMock).toHaveBeenCalledWith(ACCOUNT_ID);
    // Zero-padded width survives: the id is never coerced to a number.
    expect(typeof getAccountMock.mock.calls[0][0]).toBe('string');
    expect(acctInput()).toHaveValue(ACCOUNT_ID);
  });

  it('renders every mapped account and customer field', async () => {
    await renderLoaded();

    for (const [testId, text] of DETAIL_CELLS) {
      expect(screen.getByTestId(testId).textContent).toBe(text);
    }
  });

  it('renders monetary values and identifiers as unformatted strings', async () => {
    await renderLoaded();

    expect(screen.getByTestId('acct-credit-limit').textContent).toBe('5000.00');
    expect(screen.getByTestId('acct-cash-credit-limit').textContent).toBe('10000.00');
    expect(screen.getByTestId('acct-curr-bal').textContent).toBe('1234.56');
    expect(screen.getByTestId('acct-curr-cyc-credit').textContent).toBe('0.00');
    expect(screen.getByTestId('acct-curr-cyc-debit').textContent).toBe('375.25');
    expect(screen.getByTestId('cust-id').textContent).toBe('000000011');
    expect(acctInput()).toHaveValue('00000000011');
  });

  it('publishes the transaction id and program name to the shell', async () => {
    await renderLoaded();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CAVW');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COACTVWC');
  });

  it('waits for an account number when the route carries none', () => {
    const main = renderAt('/accounts');

    expect(getAccountMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(within(main).getByText('View Account')).toBeInTheDocument();
    expect(acctInput()).toHaveValue('');
  });
});

describe('AccountViewPage — BMS captions (COACTVW.bms)', () => {
  it('renders the account-block captions with their BMS column padding', async () => {
    const main = await renderLoaded();

    for (const caption of ACCOUNT_CAPTIONS) {
      expect(
        within(main).getByText(caption, { normalizer: EXACT_TEXT }),
      ).toBeInTheDocument();
    }

    // The padding carries the 3270 column alignment: a collapsed caption is absent.
    expect(
      within(main).queryByText('Credit Limit :', { normalizer: EXACT_TEXT }),
    ).toBeNull();
    expect(
      within(main).queryByText('Cash credit Limit :', { normalizer: EXACT_TEXT }),
    ).toBeNull();
    expect(
      within(main).queryByText('Current Balance :', { normalizer: EXACT_TEXT }),
    ).toBeNull();
  });

  it('renders the customer-block captions with their BMS column padding', async () => {
    const main = await renderLoaded();

    for (const caption of CUSTOMER_CAPTIONS) {
      expect(
        within(main).getByText(caption, { normalizer: EXACT_TEXT }),
      ).toBeInTheDocument();
    }

    expect(
      within(main).queryByText('Government Issued Id Ref :', { normalizer: EXACT_TEXT }),
    ).toBeNull();
  });

  it('renders the two NEUTRAL section headings', async () => {
    const main = await renderLoaded();

    expect(
      within(main).getByRole('heading', { level: 2, name: 'View Account' }),
    ).toBeInTheDocument();
    expect(
      within(main).getByRole('heading', { level: 3, name: 'Customer Details' }),
    ).toBeInTheDocument();
  });
});

describe('AccountViewPage — acctExpiraionDate (preserved misspelling)', () => {
  it('renders the acctExpiraionDate value under the Expiry: caption', async () => {
    const main = await renderLoaded();

    const caption = within(main).getByText('Expiry:', { normalizer: EXACT_TEXT });
    const cell = screen.getByTestId('acct-expiraion-date');

    expect(caption).toHaveAttribute('id', 'acct-expiraion-date-label');
    expect(cell).toHaveAttribute('aria-labelledby', 'acct-expiraion-date-label');
    expect(cell.textContent).toBe(account.acctExpiraionDate);
    expect(cell.textContent).toBe('2027-12-31');
    // Not one of the sibling dates carried by the same record.
    expect(cell.textContent).not.toBe(account.acctOpenDate);
    expect(cell.textContent).not.toBe(account.acctReissueDate);
  });
});

describe('AccountViewPage — read-only detail fields', () => {
  it('renders the detail values as non-editable cells', async () => {
    const main = await renderLoaded();

    const detailLists = main.querySelectorAll('dl.accountView__details');
    expect(detailLists).toHaveLength(2);
    detailLists.forEach((list) => {
      expect(
        list.querySelectorAll('input, select, textarea, button, [contenteditable="true"]'),
      ).toHaveLength(0);
    });

    for (const [testId] of DETAIL_CELLS) {
      const cell = screen.getByTestId(testId);
      expect(cell.tagName).toBe('DD');
      expect(cell).not.toHaveAttribute('contenteditable');
    }
  });

  it('leaves the account-number key field as the only editable input', async () => {
    const main = await renderLoaded();

    const textboxes = screen.getAllByRole('textbox');
    expect(textboxes).toHaveLength(1);

    const [input] = textboxes;
    expect(input).toHaveAttribute('id', 'acctsid');
    expect(input).toBeEnabled();
    expect(input).not.toHaveAttribute('readonly');
    // BMS ``ACCTSID`` is PICIN='99999999999' with VALIDN=(MUSTFILL).
    expect(input).toHaveAttribute('maxlength', '11');
    expect(main.querySelectorAll('input')).toHaveLength(1);
  });
});

describe('AccountViewPage — regulated identifier masking', () => {
  it('renders the masked Social Security number the service returns, retaining only its last four digits', async () => {
    await renderLoaded();

    const cell = screen.getByTestId('cust-ssn');
    expect(cell.textContent).toBe(MASKED_SSN);
    expect(cell.textContent).toContain('6789');
  });

  it('renders the masked government-issued id and EFT account id the service returns', async () => {
    await renderLoaded();

    expect(screen.getByTestId('cust-govt-issued-id').textContent).toBe(
      MASKED_GOVT_ISSUED_ID,
    );
    expect(screen.getByTestId('cust-eft-account-id').textContent).toBe(
      MASKED_EFT_ACCOUNT_ID,
    );
  });

  it('renders no raw regulated identifier anywhere in the document', async () => {
    await renderLoaded();

    const rendered = document.body.textContent ?? '';
    expect(rendered).not.toContain(RAW_SSN);
    expect(rendered).not.toContain(RAW_GOVT_ISSUED_ID);
    expect(rendered).not.toContain(RAW_EFT_ACCOUNT_ID);
  });
});

describe('AccountViewPage — account-number filter edit (verbatim message)', () => {
  it('rejects an all-zero account number without calling the service', () => {
    renderAt('/accounts');

    submitAccountNumber('00000000000');

    expect(screen.getByRole('alert').textContent).toBe(ACCOUNT_NUMBER_ERROR);
    expect(getAccountMock).not.toHaveBeenCalled();
  });

  it('rejects an account number shorter than eleven digits', () => {
    renderAt('/accounts');

    submitAccountNumber('1234');

    expect(screen.getByRole('alert').textContent).toBe(ACCOUNT_NUMBER_ERROR);
    expect(getAccountMock).not.toHaveBeenCalled();
  });

  it('rejects a non-numeric account number', () => {
    renderAt('/accounts');

    submitAccountNumber('1234ABCD567');

    expect(screen.getByRole('alert').textContent).toBe(ACCOUNT_NUMBER_ERROR);
    expect(getAccountMock).not.toHaveBeenCalled();
  });

  it('rejects a zero account number supplied by the route', () => {
    renderAt('/accounts/0');

    expect(screen.getByRole('alert').textContent).toBe(ACCOUNT_NUMBER_ERROR);
    expect(getAccountMock).not.toHaveBeenCalled();
  });

  it('clears the message and fetches once a valid account number is submitted', async () => {
    const main = renderAt('/accounts');

    submitAccountNumber('00000000000');
    expect(screen.getByRole('alert').textContent).toBe(ACCOUNT_NUMBER_ERROR);

    fireEvent.change(acctInput(), { target: { value: ACCOUNT_ID } });
    const form = main.querySelector('form.accountView__search');
    expect(form).not.toBeNull();
    fireEvent.submit(form as HTMLFormElement);

    await waitFor(() => {
      expect(getAccountMock).toHaveBeenCalledWith(ACCOUNT_ID);
    });
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull();
    });
    expect(getAccountMock).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(screen.getByTestId('acct-credit-limit').textContent).toBe(
      account.acctCreditLimit,
    );
  });
});

describe('AccountViewPage — function keys (BMS line 24)', () => {
  it('offers F3=Exit as the only function key', async () => {
    await renderLoaded();

    const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });
    const keys = within(toolbar).getAllByRole('button');

    expect(keys).toHaveLength(1);
    expect(keys[0]).toHaveTextContent('F3=Exit');
    expect(keys[0]).toBeEnabled();
  });

  it('exits to the menu when the F3=Exit legend button is activated', async () => {
    await renderLoaded();

    fireEvent.click(screen.getByRole('button', { name: 'F3=Exit' }));

    expect(screen.getByTestId('menu-landing')).toBeInTheDocument();
    expect(screen.queryByTestId('acct-credit-limit')).toBeNull();
  });

  it('exits to the menu when the physical F3 key is pressed', async () => {
    await renderLoaded();

    fireEvent.keyDown(document, { key: 'F3' });

    expect(screen.getByTestId('menu-landing')).toBeInTheDocument();
    expect(screen.queryByTestId('acct-credit-limit')).toBeNull();
  });
});

describe('AccountViewPage — service failures', () => {
  it('surfaces the account-not-found message and leaves the cells blank', async () => {
    getAccountMock.mockReset();
    getAccountMock.mockRejectedValue(new ApiError(404, ACCOUNT_NOT_FOUND_ERROR));

    renderAt(`/accounts/${ACCOUNT_ID}`);

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toBe(ACCOUNT_NOT_FOUND_ERROR);
    expect(screen.getByTestId('cust-id').textContent).toBe('');
    expect(screen.getByTestId('acct-credit-limit').textContent).toBe('');
  });

  it('recovers on the next submission after a failed fetch', async () => {
    getAccountMock.mockReset();
    getAccountMock.mockRejectedValueOnce(new ApiError(0, 'Unexpected error'));
    getAccountMock.mockResolvedValue(account);

    renderAt(`/accounts/${ACCOUNT_ID}`);
    expect((await screen.findByRole('alert')).textContent).toBe('Unexpected error');

    submitAccountNumber(ACCOUNT_ID);

    await waitFor(() => {
      expect(screen.getByTestId('acct-credit-limit').textContent).toBe(
        account.acctCreditLimit,
      );
    });
    expect(getAccountMock).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
