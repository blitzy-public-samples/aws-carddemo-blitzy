/**
 * Bill Pay page spec — legacy origin BMS COBIL00 / Tx CB00 /
 * program COBIL00C. Available credit server-authoritative (F-006).
 */

/**
 * billpay.test.tsx — Jest + React Testing Library component/integration suite
 * for the modern Bill Payment page (`@/app/billpay/page`, default export
 * `BillPayPage`), the redesign of the legacy 3270 screen COBIL00
 * (CICS Tx CB00, COBOL program COBIL00C; origin app/bms/COBIL00.bms +
 * app/cpy-bms/COBIL00.CPY).
 *
 * WHAT IS VERIFIED
 *   - The two-step Look Up -> Pay Balance (ConfirmDialog) -> confirm flow, the
 *     Material Design 3 redesign of the single-screen ENTER-then-confirm
 *     interaction.
 *   - `Look Up` calls `BillPayApi.GetBillPayInfo(acctId)` and renders the
 *     returned balances; `Pay Balance` opens a confirmation dialog; confirming
 *     calls `BillPayApi.PayBill({ acct_id, confirm: 'Y' })` (HTTP 200).
 *   - CRITICAL fidelity invariant (F-006): `available_credit` = credit_limit -
 *     curr_bal is SERVER-authoritative. The page renders the server
 *     `available_credit` STRING VERBATIM and performs NO client-side
 *     subtraction or reformat. The suite proves this with values where a naive
 *     client computation would differ from the server string.
 *   - Cancel path (no payment), the 11-character account-id bound
 *     (ACTIDINI PIC X(11)), and API-error surfacing on both lookup and pay.
 *
 * The real page is rendered with `@/lib/apiClient` (only `BillPayApi`) and
 * `next/navigation` mocked, so there is NO real network. `ApiError` / `IsApiError`
 * are kept REAL so a thrown `ApiError` is recognized by the shared ErrorAlert.
 * Greenfield test infra — no legacy test origin.
 */

/* --------------------------------------------------------------------------- */
/* Mocks (hoisted above imports by the swc/jest transform).                    */
/* --------------------------------------------------------------------------- */

// next/navigation — the App Router hooks. The bill-pay page is content-only and
// does not consume them, but they are mocked defensively so the suite stays
// robust if the page later reads route/query state. The `mock`-prefixed handles
// are permitted inside the hoisted factory.
const mockRouterPush = jest.fn();
const mockRouterReplace = jest.fn();
const mockRouterBack = jest.fn();
const mockRouterForward = jest.fn();
const mockRouterRefresh = jest.fn();
const mockRouterPrefetch = jest.fn();

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => ({
        push: mockRouterPush,
        replace: mockRouterReplace,
        back: mockRouterBack,
        forward: mockRouterForward,
        refresh: mockRouterRefresh,
        prefetch: mockRouterPrefetch,
    }),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/billpay',
}));

// @/lib/apiClient — keep the module REAL (ApiError, IsApiError, interceptors)
// but replace only `BillPayApi` with controllable jest fns. Spreading
// `requireActual` first, then overriding `BillPayApi`, keeps every other export
// authentic so a thrown `ApiError` still satisfies the real `IsApiError`.
jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    BillPayApi: {
        GetBillPayInfo: jest.fn(),
        PayBill: jest.fn(),
    },
}));

/* --------------------------------------------------------------------------- */
/* Imports.                                                                    */
/* --------------------------------------------------------------------------- */

import BillPayPage from '@/app/billpay/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    SetupUser,
} from '../testUtils';
import { BillPayApi, ApiError } from '@/lib/apiClient';
import type { BillPayResponse } from '@/types';

/* --------------------------------------------------------------------------- */
/* Constants (Ochs rule: ALL_UPPERCASE with underscores). Labels/text are taken */
/* VERBATIM from the real billpay/page.tsx and its dependencies.               */
/* --------------------------------------------------------------------------- */

/** Zero-padded 11-digit account id (string preserves leading zeros). */
const DEFAULT_ACCT_ID = '00000000011';

/** Accessible-name matcher for the account-id field ('Account ID', asterisk-safe). */
const ACCOUNT_ID_LABEL = /account id/i;

/** Expected `maxlength` attribute value — from ACTIDINI PIC X(11) (COBIL00.CPY). */
const ACCOUNT_ID_MAX_LENGTH = '11';

/** Look-up button label (maps the legacy ENTER account lookup). */
const LOOKUP_BUTTON_LABEL = 'Look Up';

/** Pay-balance action / dialog-confirm button label. */
const PAY_BUTTON_LABEL = 'Pay Balance';

/** ConfirmDialog cancel button label (ConfirmDialog default). */
const CANCEL_BUTTON_LABEL = 'Cancel';

/** ConfirmDialog title supplied by the page (drives the dialog accessible name). */
const CONFIRM_DIALOG_TITLE = 'Confirm Bill Payment';

/** CardHeader title shown once a lookup succeeds. */
const CARD_HEADER_TEXT = 'Account Balance';

/** Fallback success message shown when the pay response omits `message`. */
const SUCCESS_MESSAGE = 'Bill payment processed successfully.';

/* --------------------------------------------------------------------------- */
/* Typed mock handles. `clearMocks: true` clears call history between tests but */
/* never the fn objects, so capturing the handles once is safe.                */
/* --------------------------------------------------------------------------- */

const mockGetBillPayInfo = BillPayApi.GetBillPayInfo as jest.Mock;
const mockPayBill = BillPayApi.PayBill as jest.Mock;

/** A configured user-event session (the return type of {@link SetupUser}). */
type UserSession = ReturnType<typeof SetupUser>;

/* --------------------------------------------------------------------------- */
/* Fixtures (PascalCase factory — Ochs rule).                                  */
/* --------------------------------------------------------------------------- */

/**
 * Build a `BillPayResponse` fixture with realistic, string-typed money fields.
 *
 * The defaults are chosen so client-side arithmetic can be DISPROVED: with
 * credit_limit `5000.00` and curr_bal `1200.00`, a naive `credit_limit - curr_bal`
 * equals `3800.00`. Individual scenarios override `available_credit` with a value
 * that a client computation would NOT (or would format differently from) produce,
 * proving the UI shows the SERVER string.
 *
 * @param overrides - Partial fields that replace any response default.
 * @returns A fully-populated `BillPayResponse` (all money fields are strings).
 */
function MakeBillPayResponse(overrides?: Partial<BillPayResponse>): BillPayResponse {
    const baseResponse: BillPayResponse = {
        acct_id: DEFAULT_ACCT_ID,
        credit_limit: '5000.00',
        curr_bal: '1200.00',
        available_credit: '3800.00',
        payment_amount: '0.00',
    };

    return { ...baseResponse, ...overrides };
}

/* --------------------------------------------------------------------------- */
/* Test helpers (PascalCase — Ochs rule).                                      */
/* --------------------------------------------------------------------------- */

/**
 * Resolve the account-id text input by its accessible (label) name.
 *
 * @returns The account-id input element.
 */
function GetAccountIdInput(): HTMLElement {
    return screen.getByRole('textbox', { name: ACCOUNT_ID_LABEL });
}

/**
 * Render the page, arm `GetBillPayInfo` with `response`, type `acctId`, click
 * `Look Up`, and wait until the balance card has rendered.
 *
 * @param response - The lookup response the mocked API resolves with.
 * @param acctId - The account id to type; defaults to {@link DEFAULT_ACCT_ID}.
 * @returns The active user-event session (for follow-up interactions).
 */
async function RenderAndLookup(
    response: BillPayResponse,
    acctId: string = DEFAULT_ACCT_ID,
): Promise<UserSession> {
    const user = SetupUser();
    RenderWithProviders(<BillPayPage />);
    mockGetBillPayInfo.mockResolvedValueOnce(response);

    await user.type(GetAccountIdInput(), acctId);
    await user.click(screen.getByRole('button', { name: LOOKUP_BUTTON_LABEL }));

    await waitFor(() => expect(mockGetBillPayInfo).toHaveBeenCalledWith(acctId));
    await screen.findByText(CARD_HEADER_TEXT);

    return user;
}

/**
 * Click the page `Pay Balance` button (unique while the dialog is closed) and
 * return the opened confirmation dialog element.
 *
 * @param user - The active user-event session.
 * @returns The confirmation dialog element (found by its accessible name).
 */
async function OpenPaymentDialog(user: UserSession): Promise<HTMLElement> {
    await user.click(screen.getByRole('button', { name: PAY_BUTTON_LABEL }));
    return screen.findByRole('dialog', { name: CONFIRM_DIALOG_TITLE });
}

/* --------------------------------------------------------------------------- */
/* Suite.                                                                      */
/* --------------------------------------------------------------------------- */

describe('BillPayPage', () => {
    // Reset the API mocks before every test so a queued `mockResolvedValueOnce`
    // (or `mockRejectedValueOnce`) can never leak across tests. `clearMocks:true`
    // only clears call history; `mockReset` also drops implementations/queues.
    beforeEach(() => {
        mockGetBillPayInfo.mockReset();
        mockPayBill.mockReset();
    });

    // Scenario 1 — initial render -----------------------------------------------
    describe('initial render', () => {
        it('renders the account-id field and Look Up, with Pay Balance disabled', () => {
            RenderWithProviders(<BillPayPage />);

            const accountIdInput = GetAccountIdInput();
            expect(accountIdInput).toBeInTheDocument();
            expect(accountIdInput).toHaveAttribute('maxlength', ACCOUNT_ID_MAX_LENGTH);

            expect(
                screen.getByRole('button', { name: LOOKUP_BUTTON_LABEL }),
            ).toBeInTheDocument();

            // Pay Balance exists but is disabled until a lookup succeeds.
            expect(
                screen.getByRole('button', { name: PAY_BUTTON_LABEL }),
            ).toBeDisabled();

            // No confirmation dialog is mounted before the pay action.
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        });
    });

    // Scenario 2 — Look Up fetches info -----------------------------------------
    describe('look up', () => {
        it('fetches bill-pay info and displays the server balances', async () => {
            await RenderAndLookup(MakeBillPayResponse());

            // The account id is passed through verbatim (leading zeros preserved).
            expect(mockGetBillPayInfo).toHaveBeenCalledWith(DEFAULT_ACCT_ID);
            expect(mockGetBillPayInfo).toHaveBeenCalledTimes(1);

            // All three server money fields render on their labelled lines,
            // each prefixed with the shared currency symbol (QA #10 — consistent
            // with the account-view screen). The amount itself stays verbatim.
            expect(screen.getByText('Current Balance: $1200.00')).toBeInTheDocument();
            expect(screen.getByText('Credit Limit: $5000.00')).toBeInTheDocument();
            expect(screen.getByText('Available Credit: $3800.00')).toBeInTheDocument();

            // Pay Balance is enabled once balances are loaded.
            expect(
                screen.getByRole('button', { name: PAY_BUTTON_LABEL }),
            ).toBeEnabled();
        });

        it('marks the Account ID field aria-invalid and links its message via aria-describedby when Look Up is clicked empty (FINDING-10)', async () => {
            const user = SetupUser();
            RenderWithProviders(<BillPayPage />);

            // Click Look Up with an empty account id — the COBIL00C empty-id edit.
            await user.click(
                screen.getByRole('button', { name: LOOKUP_BUTTON_LABEL }),
            );

            // FINDING-10 (WCAG 3.3.1 / 4.1.2): the Account ID field is marked
            // programmatically, in addition to the verbatim COBIL00C toast, and
            // its helper text is deliberately distinct from that toast.
            const accountIdInput = GetAccountIdInput();
            expect(accountIdInput).toHaveAttribute('aria-invalid', 'true');

            const describedById = accountIdInput.getAttribute('aria-describedby');
            expect(describedById).toBeTruthy();
            expect(
                document.getElementById(describedById as string),
            ).toHaveTextContent('Account ID is required');

            // The empty-id edit blocks the network call entirely.
            expect(mockGetBillPayInfo).not.toHaveBeenCalled();
        });
    });

    // Scenario 3 — CRITICAL: available_credit is the SERVER string (F-006) ------
    describe('F-006 server-authoritative available credit', () => {
        it('renders the server available_credit verbatim without reformatting', async () => {
            // A one-decimal server string; a client `toFixed(2)` of (5000 - 1200)
            // would yield '3800.00'. The UI must show the raw server value.
            await RenderAndLookup(MakeBillPayResponse({ available_credit: '3800.5' }));

            // The currency symbol is a display prefix only; the amount is the raw
            // server string (no toFixed / rounding / padding — AAP §0.7.1).
            expect(screen.getByText('Available Credit: $3800.5')).toBeInTheDocument();
            // Neither a reformatted nor a client-computed value may appear.
            expect(
                screen.queryByText('Available Credit: $3800.50'),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByText('Available Credit: $3800.00'),
            ).not.toBeInTheDocument();
        });

        it('shows the server value even when it differs from credit_limit - curr_bal', async () => {
            // credit_limit - curr_bal = 5000.00 - 1200.00 = 3800.00, but the
            // server authoritatively returns 9999.99. The UI must trust the server.
            await RenderAndLookup(MakeBillPayResponse({ available_credit: '9999.99' }));

            expect(screen.getByText('Available Credit: $9999.99')).toBeInTheDocument();
            // The naive client subtraction result must NOT be rendered anywhere.
            expect(
                screen.queryByText('Available Credit: $3800.00'),
            ).not.toBeInTheDocument();
        });
    });

    // Scenario 4 — Pay Balance opens ConfirmDialog ------------------------------
    describe('pay balance confirmation', () => {
        it('opens a ConfirmDialog and does not call PayBill yet', async () => {
            const user = await RenderAndLookup(MakeBillPayResponse());

            const dialog = await OpenPaymentDialog(user);
            expect(dialog).toBeInTheDocument();

            // The dialog exposes its own confirm and cancel actions.
            expect(
                within(dialog).getByRole('button', { name: PAY_BUTTON_LABEL }),
            ).toBeInTheDocument();
            expect(
                within(dialog).getByRole('button', { name: CANCEL_BUTTON_LABEL }),
            ).toBeInTheDocument();

            // No payment is submitted merely by opening the dialog.
            expect(mockPayBill).not.toHaveBeenCalled();
        });

        // Scenario 5 — Confirm -> PayBill(200) ----------------------------------
        it('submits PayBill on confirm and shows success with updated balances', async () => {
            const user = await RenderAndLookup(MakeBillPayResponse());
            const dialog = await OpenPaymentDialog(user);

            // The post-payment server state: balance cleared, full credit available.
            mockPayBill.mockResolvedValueOnce(
                MakeBillPayResponse({
                    curr_bal: '0.00',
                    available_credit: '5000.00',
                    payment_amount: '1200.00',
                }),
            );

            await user.click(
                within(dialog).getByRole('button', { name: PAY_BUTTON_LABEL }),
            );

            // The request carries the account id and the legacy CONFIRM='Y' flag.
            await waitFor(() =>
                expect(mockPayBill).toHaveBeenCalledWith(
                    expect.objectContaining({
                        acct_id: DEFAULT_ACCT_ID,
                        confirm: 'Y',
                    }),
                ),
            );

            // A success message and the server-updated balances render.
            expect(await screen.findByText(SUCCESS_MESSAGE)).toBeInTheDocument();
            expect(screen.getByText('Current Balance: $0.00')).toBeInTheDocument();
            expect(screen.getByText('Available Credit: $5000.00')).toBeInTheDocument();

            // The dialog closes after a successful payment.
            await waitFor(() =>
                expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
            );
        });

        it('disables Pay Balance after a successful payment so it cannot be re-submitted (QA I24)', async () => {
            const user = await RenderAndLookup(MakeBillPayResponse());

            // A positive looked-up balance leaves Pay Balance ENABLED.
            expect(
                screen.getByRole('button', { name: PAY_BUTTON_LABEL }),
            ).toBeEnabled();

            const dialog = await OpenPaymentDialog(user);
            // The server clears the balance on a successful payment.
            mockPayBill.mockResolvedValueOnce(
                MakeBillPayResponse({
                    curr_bal: '0.00',
                    available_credit: '5000.00',
                    payment_amount: '1200.00',
                }),
            );

            await user.click(
                within(dialog).getByRole('button', { name: PAY_BUTTON_LABEL }),
            );

            // Dialog closes and the zeroed balance renders.
            await waitFor(() =>
                expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
            );
            expect(
                screen.getByText('Current Balance: $0.00'),
            ).toBeInTheDocument();

            // QA I24: with nothing left to pay, the (now unique) page Pay Balance
            // button is DISABLED -- it does not stay enabled/stale after payment.
            expect(
                screen.getByRole('button', { name: PAY_BUTTON_LABEL }),
            ).toBeDisabled();
        });

        it('keeps Pay Balance disabled after looking up a zero-balance account (QA I24)', async () => {
            // Looking up an account that has nothing to pay leaves Pay Balance
            // disabled, matching the server no-op guard for a non-positive
            // balance (legacy COBIL00C).
            await RenderAndLookup(
                MakeBillPayResponse({
                    curr_bal: '0.00',
                    available_credit: '5000.00',
                    payment_amount: '0.00',
                }),
            );

            expect(
                screen.getByRole('button', { name: PAY_BUTTON_LABEL }),
            ).toBeDisabled();
        });

        // Scenario 6 — Cancel dialog --------------------------------------------
        it('cancels the dialog without calling PayBill', async () => {
            const user = await RenderAndLookup(MakeBillPayResponse());
            const dialog = await OpenPaymentDialog(user);

            await user.click(
                within(dialog).getByRole('button', { name: CANCEL_BUTTON_LABEL }),
            );

            expect(mockPayBill).not.toHaveBeenCalled();
            await waitFor(() =>
                expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
            );
        });
    });

    // Scenario 7 — account-id maxLength 11 --------------------------------------
    describe('account-id field constraints', () => {
        it('enforces the 11-character maxLength (ACTIDINI PIC X(11))', () => {
            RenderWithProviders(<BillPayPage />);

            expect(GetAccountIdInput()).toHaveAttribute(
                'maxlength',
                ACCOUNT_ID_MAX_LENGTH,
            );
        });
    });

    // Scenario 8 — API errors on lookup / pay -----------------------------------
    describe('API errors', () => {
        it('surfaces an error alert when the lookup fails and shows no balances', async () => {
            const user = SetupUser();
            RenderWithProviders(<BillPayPage />);
            mockGetBillPayInfo.mockRejectedValueOnce(
                new ApiError({ status: 404, message: 'Account not found' }),
            );

            await user.type(GetAccountIdInput(), DEFAULT_ACCT_ID);
            await user.click(
                screen.getByRole('button', { name: LOOKUP_BUTTON_LABEL }),
            );

            // The typed ApiError message is surfaced through the shared alert.
            expect(await screen.findByRole('alert')).toHaveTextContent(
                'Account not found',
            );

            // No balance card is rendered — there is NO client arithmetic fallback.
            expect(screen.queryByText(CARD_HEADER_TEXT)).not.toBeInTheDocument();
            expect(
                screen.queryByText(/^Available Credit:/),
            ).not.toBeInTheDocument();
        });

        it('surfaces an error alert when the payment fails and closes the dialog', async () => {
            const user = await RenderAndLookup(MakeBillPayResponse());
            const dialog = await OpenPaymentDialog(user);

            mockPayBill.mockRejectedValueOnce(
                new ApiError({ status: 500, message: 'Payment failed' }),
            );

            await user.click(
                within(dialog).getByRole('button', { name: PAY_BUTTON_LABEL }),
            );

            await waitFor(() => expect(mockPayBill).toHaveBeenCalledTimes(1));

            expect(await screen.findByRole('alert')).toHaveTextContent(
                'Payment failed',
            );

            // The dialog is dismissed on failure so the user can retry.
            await waitFor(() =>
                expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
            );
        });
    });
});

