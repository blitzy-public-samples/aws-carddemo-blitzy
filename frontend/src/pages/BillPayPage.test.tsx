/**
 * BillPayPage test suite
 * ======================
 *
 * :purpose: Verify the bill-payment workflow of :func:`BillPayPage` — the React
 *     replacement for BMS mapset ``app/bms/COBIL00.bms`` (symbolic map
 *     ``app/cpy-bms/COBIL00.CPY``), CICS transaction ``CB00``, program
 *     ``app/cbl/COBIL00C.cbl`` — including the ``Y``/``N`` confirmation gate, the
 *     verbatim line-23 messages, the read-only balance, and the line-24 function
 *     keys (AAP 0.7.1).
 * :output: Jest assertions only. The page is rendered inside ``Layout``, which
 *     owns the line-23 message region and the line-24 key bar the page publishes
 *     through ``useScreenChrome``, on a ``MemoryRouter`` carrying a sentinel
 *     ``/menu`` route so PF3 navigation is observable.
 * :note: ``../api`` is mocked, so ``payBill`` is a jest mock and ``ApiError`` is
 *     the constructor every consumer sees, and neither axios nor ``import.meta``
 *     is evaluated. The mock is registered with ``jest.unstable_mockModule`` and
 *     the mocked modules are imported dynamically, the form this project's native
 *     ESM Jest runtime requires. Monetary values are asserted as the
 *     server-supplied ``string``; ``Payment successful. `` is asserted
 *     byte-exactly, trailing space included.
 */

// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import type {
  ApiErrorResponse,
  BillPayRequestDto,
  BillPayResponseDto,
} from '../types';

/** Stable mock for the ``../api`` ``payBill`` named export. */
const payBillMock =
  jest.fn<(request: BillPayRequestDto) => Promise<BillPayResponseDto>>();

/**
 * :purpose: Constructor-compatible stand-in for the real ``ApiError`` that keeps
 *     the ``instanceof`` narrowing in ``useApi`` and the ``body.message``
 *     resolution in ``BillPayPage`` intact without loading ``../api/client``.
 * :param status: HTTP status of the failure.
 * :param message: already-resolved error message.
 * :param body: standardized backend error body, when the response carried one.
 * :param isOptimisticLockConflict: ``true`` only for the HTTP ``409`` conflict.
 */
class MockApiError extends Error {
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
  // these barrel exports as well. The identity probe is left unanswered so the
  // seeded store (``__setSession``) stays the suite's only session authority.
  getSessionIdentity: jest.fn(() => new Promise<never>(() => undefined)),
  logout: jest.fn(() => Promise.resolve(undefined)),
  clearLocalCredentials: jest.fn(),
  registerSessionExpiryHandler: jest.fn(() => () => undefined),
  __esModule: true,
  payBill: payBillMock,
  // ``useSession`` — reached through the hooks barrel and through ``Layout`` —
  // imports ``signon`` from this module.
  signon: jest.fn(),
  ApiError: MockApiError,
}));

/** Page under test, bound to the mocked ``../api``. */
let BillPayPage: (typeof import('./BillPayPage'))['default'];

/** Screen shell that renders the line-23 banner and the line-24 key bar. */
let Layout: (typeof import('../components/Layout'))['default'];

/** Normalized error constructor served by the mocked ``../api``. */
let ApiError: (typeof import('../api'))['ApiError'];

/** Session test seam used to seed the signed-on user. */
let __setSession: (typeof import('../hooks/useSession'))['__setSession'];

beforeAll(async () => {
  // Imported after the mock is registered so every consumer binds to the mocked
  // module; no module reset, so they share React with Testing Library.
  ({ default: BillPayPage } = await import('./BillPayPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ ApiError } = await import('../api'));
  ({ __setSession } = await import('../hooks/useSession'));
});

/** ``COBIL00C`` L161 empty account-id message. */
const MSG_ACCT_ID_EMPTY = 'Acct ID can NOT be empty...';

/** ``COBIL00C`` L361 account-not-found message. */
const MSG_ACCOUNT_NOT_FOUND = 'Account ID NOT found...';

/** ``COBIL00C`` L237 confirm-payment prompt. */
const MSG_CONFIRM_PAYMENT = 'Confirm to make a bill payment...';

/** ``COBIL00C`` L187 invalid confirm-flag message. */
const MSG_INVALID_CONFIRM = 'Invalid value. Valid values are (Y/N)...';

/** ``COBIL00C`` L527 success-banner prefix, trailing space included. */
const MSG_PAYMENT_SUCCESSFUL = 'Payment successful. ';

/** ``COBIL00C`` L536 duplicate transaction-id message. */
const MSG_TRAN_ID_EXISTS = 'Tran ID already exist...';

/** Account id entered in ``ACTIDIN`` (``PIC X(11)``). */
const ACCOUNT_ID = '00000000011';

/** 16-digit zero-padded id of the posted bill-payment transaction. */
const TRANSACTION_ID = '0000000000000123';

/** Balance the service reports for :const:`ACCOUNT_ID` (``CURBAL``). */
const CURRENT_BALANCE = '1234.56';

/** Signed-on user id seeded into the session store. */
const SESSION_USER = 'USER0001';

/** Banner text ``COBIL00C`` composes for a completed payment. */
const PAYMENT_SUCCESSFUL_BANNER =
  `${MSG_PAYMENT_SUCCESSFUL} Your Transaction ID is ${TRANSACTION_ID}.`;

/**
 * :purpose: Build the preview response the service returns for a blank
 *     ``CONFIRM`` — the balance plus the confirm prompt, with no payment made.
 * :param currentBalance: balance echoed into ``CURBAL``.
 * :returns: the preview :ts:type:`BillPayResponseDto`.
 */
function previewResponse(currentBalance: string): BillPayResponseDto {
  return {
    accountId: ACCOUNT_ID,
    currentBalance,
    transactionId: null,
    message: MSG_CONFIRM_PAYMENT,
  };
}

/**
 * :purpose: Build the response the service returns once the payment is posted.
 * :param message: server-composed banner, or ``null`` to leave the composition
 *     to the page.
 * :returns: the success :ts:type:`BillPayResponseDto`, balance paid in full.
 */
function successResponse(message: string | null): BillPayResponseDto {
  return {
    accountId: ACCOUNT_ID,
    currentBalance: '0.00',
    transactionId: TRANSACTION_ID,
    message,
  };
}

/**
 * :purpose: Build the standardized backend error body carried by a rejected call.
 * :param status: HTTP status of the failure.
 * :param message: verbatim backend message.
 * :returns: the :ts:type:`ApiErrorResponse` body.
 */
function errorBody(status: number, message: string): ApiErrorResponse {
  return {
    timestamp: '2026-01-15T10:15:30.123456',
    status,
    error: status === 404 ? 'Not Found' : 'Bad Request',
    message,
    path: '/billpay',
    correlationId: 'test-correlation-id',
  };
}

/**
 * :purpose: Render the bill-payment screen inside the shared ``Layout`` shell on
 *     the ``/billpay`` route.
 * :returns: the Testing Library render result.
 */
function renderBillPayScreen(): RenderResult {
  return render(
    <MemoryRouter initialEntries={['/billpay']}>
      <Routes>
        <Route
          path="/billpay"
          element={
            <Layout>
              <BillPayPage />
            </Layout>
          }
        />
        <Route
          path="/menu"
          element={<div data-testid="main-menu-route">MAIN MENU</div>}
        />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * :purpose: The ``ACTIDIN`` account-id entry field.
 * :returns: the account-id input.
 */
function acctIdField(): HTMLInputElement {
  return screen.getByTestId<HTMLInputElement>('acct-id');
}

/**
 * :purpose: The protected ``CURBAL`` balance field. ``DFHBMPRF`` makes it output
 *     only, so the screen renders it as a labelled output cell rather than as a
 *     read-only entry field.
 * :returns: the current-balance output cell.
 */
function currBalField(): HTMLElement {
  return screen.getByTestId('cur-bal');
}

/**
 * :purpose: The single-character ``CONFIRM`` field.
 * :returns: the confirmation input.
 */
function confirmField(): HTMLInputElement {
  return screen.getByTestId<HTMLInputElement>('confirm');
}

/**
 * :purpose: The line-24 legend button carrying a caption.
 * :param caption: exact button caption, for example ``ENTER=Continue``.
 * :returns: the legend button.
 */
function pfKey(caption: string): HTMLElement {
  return screen.getByRole('button', { name: caption });
}

beforeEach(() => {
  payBillMock.mockReset();
  act(() => {
    __setSession(SESSION_USER, 'U');
  });
});

afterEach(() => {
  act(() => {
    __setSession(null, null);
  });
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

describe('BillPayPage — screen fields and widths (COBIL00 / COBIL00.CPY)', () => {
  it('renders ACTIDIN as an 11-character entry field bound to its label', () => {
    renderBillPayScreen();

    const field = acctIdField();
    expect(field).toHaveAttribute('maxLength', '11');
    expect(field).toHaveAttribute('size', '11');
    expect(field).toHaveValue('');
    expect(screen.getByLabelText('Enter Acct ID:')).toBe(field);
  });

  it('displays CURBAL as a protected output cell at its 14-character width', () => {
    renderBillPayScreen();

    const balance = currBalField();
    // A protected field is not an entry field, so it is neither focusable nor
    // enterable; it keeps the mapset's 14-column footprint.
    expect(balance.tagName).toBe('DD');
    expect(balance).not.toHaveAttribute('tabindex');
    expect(balance.style.minWidth).toBe('14ch');
    expect(balance.textContent).toBe('');
    const caption = screen.getByText('Your current balance is:');
    expect(caption).toBeInTheDocument();
    expect(balance).toHaveAttribute('aria-labelledby', caption.id);
  });

  it('renders the single-character CONFIRM field with its (Y/N) legend', () => {
    renderBillPayScreen();

    const confirm = confirmField();
    expect(confirm).toHaveAttribute('maxLength', '1');
    expect(confirm).toHaveValue('');
    expect(screen.getByText('(Y/N)')).toBeInTheDocument();
    expect(
      screen.getByLabelText('Do you want to pay your balance now. Please confirm:'),
    ).toBe(confirm);
  });

  it('publishes the CB00 / COBIL00C chrome with an empty line-23 region', () => {
    const { container } = renderBillPayScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CB00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COBIL00C');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    expect(
      screen.getByRole('heading', { level: 3, name: 'Bill Payment' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(container.querySelector('.screen')).toHaveAttribute(
      'data-authenticated',
      'true',
    );
  });
});

describe('BillPayPage — account-id validation (COBIL00C PROCESS-ENTER-KEY)', () => {
  it('rejects an empty account id with the verbatim message', async () => {
    const user = userEvent.setup();
    renderBillPayScreen();

    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_ACCT_ID_EMPTY);
    expect(payBillMock).not.toHaveBeenCalled();
    expect(acctIdField()).toHaveFocus();
  });

  it('treats an all-blank account id as empty (COBOL SPACES check)', async () => {
    const user = userEvent.setup();
    renderBillPayScreen();

    await user.type(acctIdField(), '   ');
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_ACCT_ID_EMPTY);
    expect(payBillMock).not.toHaveBeenCalled();
  });

  it('surfaces the backend account-not-found message verbatim', async () => {
    const user = userEvent.setup();
    payBillMock.mockRejectedValue(
      new ApiError(404, MSG_ACCOUNT_NOT_FOUND, errorBody(404, MSG_ACCOUNT_NOT_FOUND)),
    );
    renderBillPayScreen();

    await user.type(acctIdField(), '99999999999');
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_ACCOUNT_NOT_FOUND);
    expect(payBillMock).toHaveBeenCalledWith({
      accountId: '99999999999',
      confirm: '',
    });
    expect(currBalField().textContent).toBe('');
  });
});

describe('BillPayPage — confirmation gate (COBIL00C CONFIRM Y/N)', () => {
  it('prompts for confirmation and shows the balance on the first ENTER', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse(CURRENT_BALANCE));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_CONFIRM_PAYMENT);
    expect(payBillMock).toHaveBeenCalledTimes(1);
    expect(payBillMock).toHaveBeenCalledWith({
      accountId: ACCOUNT_ID,
      confirm: '',
    });
    expect(currBalField().textContent).toBe(CURRENT_BALANCE);
    expect(confirmField()).toHaveFocus();
  });

  it('displays the balance exactly as the service sent it', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse('1000.00'));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));

    await waitFor(() => {
      expect(currBalField().textContent).toBe('1000.00');
    });
    // The money string is surfaced byte-for-byte: no parsing, rounding or
    // reformatting between the wire value and CURBAL.
    expect(currBalField().textContent).toBe('1000.00');
  });

  it('rejects a confirmation other than Y or N with the verbatim message', async () => {
    const user = userEvent.setup();
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'X');
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_INVALID_CONFIRM);
    expect(payBillMock).not.toHaveBeenCalled();
    expect(confirmField()).toHaveFocus();
  });

  it('accepts a lower-case y as confirmation (COBOL WHEN y)', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(successResponse(null));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'y');
    await user.click(pfKey('ENTER=Continue'));

    await waitFor(() => {
      expect(payBillMock).toHaveBeenCalledWith({
        accountId: ACCOUNT_ID,
        confirm: 'y',
      });
    });
  });
});

describe('BillPayPage — successful payment (COBIL00C WRITE-TRANSACT-FILE)', () => {
  it('posts the payment and surfaces the server banner verbatim', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(successResponse(PAYMENT_SUCCESSFUL_BANNER));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'Y');
    await user.click(pfKey('ENTER=Continue'));

    const banner = await findInfoBanner();
    expect(banner.textContent).toBe(PAYMENT_SUCCESSFUL_BANNER);
    // 'Payment successful. ' keeps its trailing space, so the composed banner
    // carries the two spaces the COBOL STRING produced.
    expect(banner.textContent?.startsWith(MSG_PAYMENT_SUCCESSFUL)).toBe(true);
    expect(banner.textContent).toContain('Payment successful.  Your Transaction ID is');
    expect(payBillMock).toHaveBeenCalledTimes(1);
    expect(payBillMock).toHaveBeenCalledWith({
      accountId: ACCOUNT_ID,
      confirm: 'Y',
    });
  });

  it('composes the banner as COBIL00C does when the service sends none', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(successResponse(null));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'Y');
    await user.click(pfKey('ENTER=Continue'));

    const banner = await findInfoBanner();
    expect(banner.textContent).toBe(PAYMENT_SUCCESSFUL_BANNER);
    expect(banner.textContent?.startsWith(MSG_PAYMENT_SUCCESSFUL)).toBe(true);
  });

  it('clears the entry fields once the payment is posted', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(successResponse(PAYMENT_SUCCESSFUL_BANNER));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'Y');
    await user.click(pfKey('ENTER=Continue'));

    await findInfoBanner();
    expect(acctIdField()).toHaveValue('');
    expect(currBalField().textContent).toBe('');
    expect(confirmField()).toHaveValue('');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(acctIdField()).toHaveFocus();
  });
});

describe('BillPayPage — duplicate transaction id (COBOL DUPKEY/DUPREC)', () => {
  it('surfaces the duplicate message verbatim when the write is rejected', async () => {
    const user = userEvent.setup();
    payBillMock.mockRejectedValue(
      new ApiError(400, MSG_TRAN_ID_EXISTS, errorBody(400, MSG_TRAN_ID_EXISTS)),
    );
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'Y');
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_TRAN_ID_EXISTS);
    expect(payBillMock).toHaveBeenCalledWith({
      accountId: ACCOUNT_ID,
      confirm: 'Y',
    });
    expect(infoBanner()).toBeNull();
  });
});

describe('BillPayPage — cancellation (COBIL00C CLEAR-CURRENT-SCREEN)', () => {
  it('does not call payBill when the confirmation is N', async () => {
    const user = userEvent.setup();
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'N');
    await user.click(pfKey('ENTER=Continue'));

    expect(payBillMock).not.toHaveBeenCalled();
    expect(acctIdField()).toHaveValue('');
    expect(confirmField()).toHaveValue('');
    expect(currBalField().textContent).toBe('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
  });

  it('cancels on a lower-case n as well (COBOL WHEN n)', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse(CURRENT_BALANCE));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));
    await waitFor(() => {
      expect(currBalField().textContent).toBe(CURRENT_BALANCE);
    });

    await user.type(confirmField(), 'n');
    await user.click(pfKey('ENTER=Continue'));

    expect(payBillMock).toHaveBeenCalledTimes(1);
    expect(acctIdField()).toHaveValue('');
    expect(confirmField()).toHaveValue('');
    expect(currBalField().textContent).toBe('');
  });
});

describe('BillPayPage — line-24 function keys (COBIL00.bms FKEYS)', () => {
  it('publishes the legend captions in mapset order', () => {
    renderBillPayScreen();

    expect(
      screen.getByRole('toolbar', { name: 'Function keys' }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      'ENTER=Continue',
      'F3=Back',
      'F4=Clear',
    ]);
  });

  it('returns to the main menu when F3 is activated', async () => {
    const user = userEvent.setup();
    renderBillPayScreen();

    await user.click(pfKey('F3=Back'));

    expect(screen.getByTestId('main-menu-route')).toBeInTheDocument();
    expect(payBillMock).not.toHaveBeenCalled();
  });

  it('returns to the main menu on the physical F3 key', () => {
    renderBillPayScreen();

    fireEvent.keyDown(document, { key: 'F3' });

    expect(screen.getByTestId('main-menu-route')).toBeInTheDocument();
  });

  it('blanks every field when F4 is activated', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse(CURRENT_BALANCE));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));
    await waitFor(() => {
      expect(currBalField().textContent).toBe(CURRENT_BALANCE);
    });

    await user.click(pfKey('F4=Clear'));

    expect(acctIdField()).toHaveValue('');
    expect(currBalField().textContent).toBe('');
    expect(confirmField()).toHaveValue('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
  });

  it('submits the screen on the physical ENTER key', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse(CURRENT_BALANCE));
    renderBillPayScreen();

    await user.type(acctIdField(), `${ACCOUNT_ID}{Enter}`);

    await waitFor(() => {
      expect(payBillMock).toHaveBeenCalledWith({
        accountId: ACCOUNT_ID,
        confirm: '',
      });
    });
    expect((await screen.findByRole('alert')).textContent).toBe(MSG_CONFIRM_PAYMENT);
  });
});

