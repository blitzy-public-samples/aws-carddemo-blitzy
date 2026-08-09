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

/** The shared session harness, which seeds identity over ``GET /session``. */
type SessionHarness = typeof import('../testing/sessionHarness');

/** Establishes the signed-on user this screen is exercised as. */
let seedSignedOnSession: SessionHarness['seedSignedOnSession'];

/** Returns the store to the server-confirmed signed-out state between tests. */
let seedSignedOutSession: SessionHarness['seedSignedOutSession'];

beforeAll(async () => {
  // Imported after the mock is registered so every consumer binds to the mocked
  // module; no module reset, so they share React with Testing Library.
  ({ default: BillPayPage } = await import('./BillPayPage'));
  ({ default: Layout } = await import('../components/Layout'));
  ({ ApiError } = await import('../api'));
  ({ seedSignedOnSession, seedSignedOutSession } = await import(
    '../testing/sessionHarness'
  ));
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

/**
 * The same balance as the screen displays it. ``COBIL00C`` L193-194 MOVEs
 * ``ACCT-CURR-BAL`` through ``WS-CURR-BAL PIC +9999999999.99`` into ``CURBALI``, whose
 * map field is declared ``LENGTH=14``, so the balance is an edited picture on the screen
 * rather than the bare wire value.
 */
const CURRENT_BALANCE_PICTURE = '+0000001234.56';

/** Signed-on user id seeded into the session store. */
const SESSION_USER = 'USER0001';

/** Banner text ``COBIL00C`` composes for a completed payment. */
/** ``COBIL00C`` zero-or-negative balance message (L201). */
const MSG_NOTHING_TO_PAY = 'You have nothing to pay...';

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
/**
 * :purpose: The response the service returns for the nothing-to-pay outcome: the
 *     balance IS carried, and the message travels on the ERROR channel.
 * :param currentBalance: the zero-or-negative balance the screen must display.
 * :returns: a nothing-to-pay response.
 */
function nothingToPayResponse(currentBalance: string): BillPayResponseDto {
  return {
    accountId: ACCOUNT_ID,
    currentBalance,
    transactionId: null,
    message: null,
    errorMessage: MSG_NOTHING_TO_PAY,
  };
}

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

beforeEach(async () => {
  payBillMock.mockReset();
  await seedSignedOnSession(SESSION_USER, 'U');
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
    // The cursor returns to ACTIDIN, so that is the field the message is about -- and a
    // message the SERVER produced marks it exactly as a locally rejected value does:
    // the accessible flag and the cursor, with no colour, because COBIL00C contains no
    // `MOVE DFHRED`.
    expect(acctIdField()).toBeInvalid();
    expect(acctIdField()).toHaveClass('field');
    expect(acctIdField()).not.toHaveClass('fieldError');
    expect(confirmField()).not.toBeInvalid();
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
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
    expect(currBalField().textContent).toBe(CURRENT_BALANCE_PICTURE);
    expect(confirmField()).toHaveFocus();
  });

  it('edits the balance into the CURBAL picture without changing its value', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse('1000.00'));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));

    await waitFor(() => {
      expect(currBalField().textContent).toBe('+0000001000.00');
    });
    // Sign, ten zero-padded integer digits, point, two decimals — the fourteen
    // characters CURBAL declares. The digits are the wire value's own: nothing is
    // parsed, rounded or rescaled between the response and the screen.
    expect(currBalField().textContent).toHaveLength(14);
  });

  it('edits a negative balance with its sign, as PIC +9999999999.99 does', async () => {
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(previewResponse('-919.00'));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));

    await waitFor(() => {
      expect(currBalField().textContent).toBe('-0000000919.00');
    });
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

describe('BillPayPage — in-flight duplicate-payment guard', () => {
  it('closes both entry fields while the payment is in flight and posts once for repeated ENTER', async () => {
    const user = userEvent.setup();
    let acknowledge!: (value: BillPayResponseDto) => void;
    payBillMock.mockReturnValueOnce(
      new Promise<BillPayResponseDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.type(confirmField(), 'Y');
    await user.click(pfKey('ENTER=Continue'));

    // ``ATTRB=ASKIP`` for the whole in-flight interval: the operator cannot retype the
    // account or the confirmation while the payment POST is outstanding.
    expect(acctIdField()).toBeDisabled();
    expect(confirmField()).toBeDisabled();
    // The keyboard is locked for the same interval, so the row-24 legend renders
    // inactive too rather than inviting an AID the screen would discard.
    expect(pfKey('ENTER=Continue')).toBeDisabled();
    expect(pfKey('F3=Back')).toBeDisabled();
    expect(pfKey('F4=Clear')).toBeDisabled();

    // A second ENTER, from the legend and from the physical key, must not pay twice.
    act(() => {
      fireEvent.click(pfKey('ENTER=Continue'));
      fireEvent.keyDown(document, { key: 'Enter' });
    });
    expect(payBillMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      acknowledge(successResponse(PAYMENT_SUCCESSFUL_BANNER));
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(payBillMock).toHaveBeenCalledTimes(1);
    expect(acctIdField()).toBeEnabled();
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
      expect(currBalField().textContent).toBe(CURRENT_BALANCE_PICTURE);
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
      screen.getByRole('group', { name: 'Function keys' }),
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
      expect(currBalField().textContent).toBe(CURRENT_BALANCE_PICTURE);
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

describe('BillPayPage — the balance belongs to the account beside it (COBIL00C L193-204)', () => {
  it('displays a zero balance together with the nothing-to-pay message', async () => {
    // COBIL00C moves ACCT-CURR-BAL into CURBALI at L193-194, BEFORE the L198
    // `IF ACCT-CURR-BAL <= ZEROS` test, so the send that carries this message carries the
    // balance beside it. Refusing the turn with an error status instead withheld the
    // balance entirely, and an account paid down to zero could never show one again.
    const user = userEvent.setup();
    payBillMock.mockResolvedValue(nothingToPayResponse('0.00'));
    renderBillPayScreen();

    await user.type(acctIdField(), ACCOUNT_ID);
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_NOTHING_TO_PAY);
    // L56 declares WS-CURR-BAL as PIC +9999999999.99, so the field carries the edited
    // picture the program moves into CURBALI, sign and zero-fill included.
    expect(currBalField().textContent).toBe('+0000000000.00');
    // L202 cursors ACTIDIN for this outcome, not CONFIRM.
    expect(acctIdField()).toHaveFocus();
  });

  it('replaces the previous account balance when a second account is read', async () => {
    // The reported defect: account 3's id displayed beside account 1's money. Both turns
    // are answered, so both repaint the field from the account just read.
    const user = userEvent.setup();
    payBillMock.mockResolvedValueOnce(previewResponse('194.00'));
    renderBillPayScreen();

    await user.type(acctIdField(), '00000000001');
    await user.click(pfKey('ENTER=Continue'));
    expect(currBalField().textContent).toBe('+0000000194.00');

    payBillMock.mockResolvedValueOnce(nothingToPayResponse('0.00'));
    await user.clear(acctIdField());
    await user.type(acctIdField(), '00000000003');
    await user.click(pfKey('ENTER=Continue'));

    expect(currBalField().textContent).toBe('+0000000000.00');
    expect(acctIdField()).toHaveValue('00000000003');
  });

  it('leaves the displayed balance in place when the read itself fails', async () => {
    // Faithful, not a leak. `CURBAL ATTRB=(ASKIP,FSET,NORM)` and `COBIL0AO REDEFINES
    // COBIL0AI` put CURBALO and CURBALI on the same fourteen bytes, so the FSET tag
    // returns the displayed value on RECEIVE and the send echoes it back -- and a failed
    // read sends the map from inside READ-ACCTDAT-FILE, before the L193 move that would
    // have replaced it.
    const user = userEvent.setup();
    payBillMock.mockResolvedValueOnce(previewResponse('194.00'));
    renderBillPayScreen();

    await user.type(acctIdField(), '00000000001');
    await user.click(pfKey('ENTER=Continue'));
    expect(currBalField().textContent).toBe('+0000000194.00');

    payBillMock.mockRejectedValueOnce(
      new ApiError(404, MSG_ACCOUNT_NOT_FOUND, errorBody(404, MSG_ACCOUNT_NOT_FOUND)),
    );
    await user.clear(acctIdField());
    await user.type(acctIdField(), '99999999999');
    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_ACCOUNT_NOT_FOUND);
    expect(currBalField().textContent).toBe('+0000000194.00');
  });

  it('paints the balance caption at its declared width of 25 characters', () => {
    // LENGTH=25 at POS=(11,6): twenty-four visible characters plus the trailing pad
    // column that separates the caption from CURBAL at column 32.
    renderBillPayScreen();

    // OutputField renders the pair inside its own `.detailField` wrapper, which is what
    // the shared `white-space: pre` rule keys off -- so the trailing column survives
    // rendering and the only thing that had dropped it was the JSX literal itself.
    const caption = document.querySelector('.billPay__row .detailField > dt');
    expect(caption?.textContent).toBe('Your current balance is: ');
    expect(caption?.textContent).toHaveLength(25);
    // Whether that column SURVIVES rendering is a stylesheet property (`white-space: pre`
    // on `.detailField > dt`), which jsdom does not load; it is measured in the browser.
  });

  it('paints the account caption GREEN, as COBIL00 declares it', () => {
    // `'Enter Acct ID:'` is LENGTH=14 POS=(6,6) COLOR=GREEN -- the one entry caption in
    // the app that is not the TURQUOISE `.prompt` tone.
    renderBillPayScreen();

    const caption = document.querySelector("label[for='billPayAcctId']");
    expect(caption?.className).toContain('green');
    expect(caption?.className).not.toContain('prompt');
    expect(caption?.textContent).toBe('Enter Acct ID:');
  });

  it('describes the confirmation field with its own (Y/N) value hint', () => {
    // The one-character CONFIRM field takes 'Y' or 'N'; the `(Y/N)` literal at
    // POS=(15,63) is what tells the operator so, and it is the accessible
    // description of the control it sits beside.
    renderBillPayScreen();

    const described = confirmField().getAttribute('aria-describedby') ?? '';
    expect(described.split(' ')).toContain('billPayConfirmValues');
    expect(document.getElementById('billPayConfirmValues')?.textContent).toBe('(Y/N)');
  });

  /*
   * COBIL00C contains no ``MOVE DFHRED`` and no ``MOVE '*'``: a rejected entry is
   * marked by ``MOVE -1 TO ACTIDINL`` -- the cursor -- and the line-23 message. The
   * accessible flag has no BMS analogue to contradict and is not visible output, so
   * it travels alone; a red frame here would be output the mapset never declares.
   */
  it('marks a rejected entry by cursor and flag alone, never by colour', async () => {
    const user = userEvent.setup();
    renderBillPayScreen();

    await user.click(pfKey('ENTER=Continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(MSG_ACCT_ID_EMPTY);
    expect(acctIdField()).toHaveAttribute('aria-invalid', 'true');
    expect(acctIdField()).toHaveFocus();
    expect(acctIdField().className).not.toContain('fieldError');
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
  });
});
