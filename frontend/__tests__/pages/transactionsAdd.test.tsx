/** Add Transaction page spec — legacy origin BMS COTRN02 / Tx CT02 / program COTRN02C. Posting codes 100-103/109 preserved. */

/*
 * Component/integration spec for the modern Add-Transaction page
 * (`@/app/transactions/add/page`, default export `TransactionsAddPage`), the
 * MD3/MUI redesign of the legacy 3270 map COTRN02 (CICS tx CT02, program
 * COTRN02C). The spec renders the REAL page with only `@/lib/apiClient`
 * (`TransactionsApi`) and `next/navigation` mocked — there is no real network.
 *
 * Two behaviors are asserted with the greatest rigor because they are the
 * business contract ported from the mainframe (AAP §0.7.3 / §0.8.1):
 *   1. The write is GATED behind a confirm dialog: `TransactionsApi.AddTransaction`
 *      is only invoked AFTER the operator confirms.
 *   2. Server posting-validation rejections (HTTP 400 `ApiError` carrying the
 *      numeric `code` 100/101/102/103/109) are surfaced verbatim through
 *      `ErrorAlert`, which maps each code to its CBTRN02C reason description.
 *
 * `ApiError` / `IsApiError` are kept REAL (via `jest.requireActual`) so the
 * `instanceof` narrowing inside `ErrorAlert` resolves against the same class the
 * spec constructs; only `TransactionsApi` is replaced with jest mocks.
 */

import TransactionsAddPage from '@/app/transactions/add/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    SetupUser,
} from '../testUtils';
import { TransactionsApi, ApiError, IsApiError } from '@/lib/apiClient';

import type { TransactionCreate, TransactionRead } from '@/types';

/* ------------------------------------------------------------------------- */
/* Mocks. `next/navigation` router is faked so `useRouter().push` is a spy;   */
/* `@/lib/apiClient` keeps every real export (ApiError/IsApiError/apiClient)  */
/* and replaces ONLY `TransactionsApi` with jest mocks.                       */
/* ------------------------------------------------------------------------- */

/** Router `push` spy (mock-prefixed so the jest.mock factory may close over it). */
const mockPush = jest.fn();

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => ({
        push: mockPush,
        replace: jest.fn(),
        back: jest.fn(),
        forward: jest.fn(),
        refresh: jest.fn(),
        prefetch: jest.fn(),
    }),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/transactions/add',
}));

jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual<typeof import('@/lib/apiClient')>('@/lib/apiClient'),
    TransactionsApi: {
        ListTransactions: jest.fn(),
        GetTransaction: jest.fn(),
        AddTransaction: jest.fn(),
    },
}));

/* ------------------------------------------------------------------------- */
/* Constants (Ochs rule: ALL_UPPERCASE). Text mirrors the real page/components*/
/* verbatim so the spec fails loudly if the user-facing wording drifts.       */
/* ------------------------------------------------------------------------- */

/** Screen heading AND confirm-dialog title (identical text — scope carefully). */
const PAGE_HEADING = 'Add Transaction';

/** Confirm-dialog prompt (unique to the dialog). */
const CONFIRM_MESSAGE = 'You are about to add this transaction. Please confirm.';

/** Success banner prefix emitted on a 201 add. */
const SUCCESS_MESSAGE = 'Transaction added successfully.';

/** Action-button labels (the main "Add" and the dialog confirm share "Add"). */
const BUTTON_ADD = 'Add';
const BUTTON_CLEAR = 'Clear';
const BUTTON_BACK = 'Back';
const BUTTON_CANCEL = 'Cancel';

/* Client-side validation messages, copied verbatim from the page. */
const ACCT_OR_CARD_REQUIRED_ERROR = 'Account or Card Number must be entered...';
const TYPE_CD_EMPTY_ERROR = 'Type CD can NOT be empty...';
const AMOUNT_EMPTY_ERROR = 'Amount can NOT be empty...';
const AMOUNT_FORMAT_ERROR = 'Amount should be in format -99999999.99';
const ORIG_DATE_FORMAT_ERROR = 'Orig Date should be in format YYYY-MM-DD';

/** A valid zero-padded 11-digit account id (string preserves leading zeros). */
const VALID_ACCT_ID = '00000000011';

/** A valid 16-digit card number for the card-only key-rule scenario. */
const VALID_CARD_NUM = '4111111111111111';

/** Tran id echoed back by a successful add (used in the success banner). */
const DEFAULT_TRAN_ID = '0000000000000042';

/**
 * The five posting reason codes and their CBTRN02C descriptions, paired with a
 * case-insensitive pattern used to assert the surfaced ErrorAlert text. Codes
 * 101 and 109 intentionally share a description, matching the mainframe source.
 */
const POSTING_CODE_CASES: ReadonlyArray<readonly [string, string, RegExp]> = [
    ['100', 'INVALID CARD NUMBER FOUND', /invalid card number/i],
    ['101', 'ACCOUNT RECORD NOT FOUND', /account record not found/i],
    ['102', 'OVERLIMIT TRANSACTION', /overlimit transaction/i],
    ['103', 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION', /after acct expiration/i],
    ['109', 'ACCOUNT RECORD NOT FOUND', /account record not found/i],
];

/**
 * Field name → visible label, in render order. Labels/lengths come from the
 * real page (derived from COTRN02.bms / COTRN02.CPY). Drives both the
 * render-completeness assertions and the form-fill helper.
 */
const FIELD_LABELS: ReadonlyArray<readonly [keyof TransactionCreate, string]> = [
    ['acct_id', 'Enter Acct #:'],
    ['card_num', 'Card #:'],
    ['tran_type_cd', 'Type CD:'],
    ['tran_cat_cd', 'Category CD:'],
    ['tran_source', 'Source:'],
    ['tran_desc', 'Description:'],
    ['tran_amt', 'Amount:'],
    ['orig_ts', 'Orig Date:'],
    ['proc_ts', 'Proc Date:'],
    ['merchant_id', 'Merchant ID:'],
    ['merchant_name', 'Merchant Name:'],
    ['merchant_city', 'Merchant City:'],
    ['merchant_zip', 'Merchant Zip:'],
];

/* ------------------------------------------------------------------------- */
/* Fixtures. `MakeTransactionCreate` returns a fully valid 13-field body that */
/* passes every client regex; `MakeTransactionRead` is the 201 response.      */
/* ------------------------------------------------------------------------- */

/** A convenient alias for the configured user-event session type. */
type TestUser = ReturnType<typeof SetupUser>;

/**
 * Build a valid add-transaction body. Every field satisfies the page's client
 * regexes (numeric `/^\d+$/`, amount `/^[+-]\d{8}\.\d{2}$/`, date
 * `/^\d{4}-\d{2}-\d{2}$/`); the account id is supplied and the card left blank,
 * exercising the account-side of the account-OR-card key rule.
 *
 * @param overrides - Partial fields that replace any default.
 * @returns A fully valid `TransactionCreate`.
 */
function MakeTransactionCreate(
    overrides?: Partial<TransactionCreate>,
): TransactionCreate {
    const baseTransaction: TransactionCreate = {
        acct_id: VALID_ACCT_ID,
        card_num: '',
        tran_type_cd: '01',
        tran_cat_cd: '0005',
        tran_source: 'POS',
        tran_desc: 'GROCERY PURCHASE',
        tran_amt: '+00000100.00',
        merchant_id: '000000001',
        merchant_name: 'ACME STORE',
        merchant_city: 'ANYTOWN',
        merchant_zip: '12345',
        orig_ts: '2024-01-15',
        proc_ts: '2024-01-16',
    };

    return { ...baseTransaction, ...overrides };
}

/**
 * Build the `TransactionRead` a successful (201) add resolves with. `card_num`
 * is already masked and there is deliberately no `cvv` field.
 *
 * @param overrides - Partial fields that replace any default.
 * @returns A valid `TransactionRead`.
 */
function MakeTransactionRead(
    overrides?: Partial<TransactionRead>,
): TransactionRead {
    const baseRead: TransactionRead = {
        tran_id: DEFAULT_TRAN_ID,
        tran_type_cd: '01',
        tran_cat_cd: '0005',
        tran_source: 'POS',
        tran_desc: 'GROCERY PURCHASE',
        tran_amt: '100.00',
        merchant_id: '000000001',
        merchant_name: 'ACME STORE',
        merchant_city: 'ANYTOWN',
        merchant_zip: '12345',
        card_num: '************1234',
        orig_ts: '2024-01-15T00:00:00Z',
        proc_ts: '2024-01-16T00:00:00Z',
    };

    return { ...baseRead, ...overrides };
}

/* ------------------------------------------------------------------------- */
/* Helpers (PascalCase per the Ochs rule). Small, single-purpose.            */
/* ------------------------------------------------------------------------- */

/**
 * Render the page inside the real MUI theme and return a fresh user-event
 * session. Router and API are already mocked at module scope.
 *
 * @returns The configured user-event session for driving interactions.
 */
function RenderAddPage(): TestUser {
    const user = SetupUser();
    RenderWithProviders(<TransactionsAddPage />);
    return user;
}

/**
 * Type each non-empty field of `values` into its labelled input via user-event
 * (honoring `FormField.onChange(name, value)`). Empty values are skipped so a
 * blank `card_num`/`acct_id` stays untouched for key-rule scenarios.
 *
 * @param user - The active user-event session.
 * @param values - The transaction body to enter.
 */
async function FillTransactionForm(
    user: TestUser,
    values: TransactionCreate,
): Promise<void> {
    for (const [fieldName, label] of FIELD_LABELS) {
        const fieldValue = values[fieldName];
        if (fieldValue === '') {
            continue;
        }
        const input = screen.getByLabelText(label);
        await user.clear(input);
        await user.type(input, fieldValue);
    }
}

/**
 * Click the main "Add" button (the only "Add" while the dialog is closed) and
 * wait for the confirm dialog to appear.
 *
 * @param user - The active user-event session.
 * @returns The opened dialog element for `within`-scoped queries.
 */
async function OpenConfirmDialog(user: TestUser): Promise<HTMLElement> {
    await user.click(screen.getByRole('button', { name: BUTTON_ADD }));
    return screen.findByRole('dialog');
}

describe('TransactionsAddPage', () => {
    // Fully RESET the TransactionsApi mocks before every test. The global
    // `clearMocks: true` only calls `mockClear()` (resets call records); it does
    // NOT drain the `mockResolvedValueOnce`/`mockRejectedValueOnce` implementation
    // queue. `mockReset()` drains that queue too, so a one-shot behavior armed by
    // one test can never leak into a later one (deterministic isolation).
    beforeEach(() => {
        jest.mocked(TransactionsApi.AddTransaction).mockReset();
        jest.mocked(TransactionsApi.ListTransactions).mockReset();
        jest.mocked(TransactionsApi.GetTransaction).mockReset();
    });

    // ----------------------------------------------------------------------
    // 0. Mock wiring — the whole suite depends on ApiError/IsApiError staying
    //    real (only TransactionsApi is mocked). Guard that assumption.
    // ----------------------------------------------------------------------

    describe('mock wiring', () => {
        it('keeps ApiError and IsApiError real for posting-code narrowing', () => {
            const postingError = new ApiError({
                status: 400,
                code: '102',
                message: 'OVERLIMIT TRANSACTION',
            });

            expect(IsApiError(postingError)).toBe(true);
            expect(IsApiError(new Error('plain error'))).toBe(false);
            expect(postingError.code).toBe('102');
            expect(postingError.status).toBe(400);
        });
    });

    // ----------------------------------------------------------------------
    // 1. Rendering
    // ----------------------------------------------------------------------

    describe('rendering', () => {
        it('renders all entry fields, action buttons, and the heading', () => {
            RenderAddPage();

            expect(
                screen.getByRole('heading', { name: PAGE_HEADING }),
            ).toBeInTheDocument();

            for (const [, label] of FIELD_LABELS) {
                expect(screen.getByLabelText(label)).toBeInTheDocument();
            }

            expect(
                screen.getByRole('button', { name: BUTTON_ADD }),
            ).toBeInTheDocument();
            expect(
                screen.getByRole('button', { name: BUTTON_CLEAR }),
            ).toBeInTheDocument();
            expect(
                screen.getByRole('button', { name: BUTTON_BACK }),
            ).toBeInTheDocument();

            // Nothing is posted merely by rendering the form.
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });
    });

    // ----------------------------------------------------------------------
    // 2. Client-side validation blocks submit (no dialog, no POST)
    // ----------------------------------------------------------------------

    describe('client-side validation', () => {
        it('blocks submit and shows errors when required fields are empty', async () => {
            const user = RenderAddPage();

            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            // Representative errors from each validator group must surface.
            expect(
                await screen.findByText(ACCT_OR_CARD_REQUIRED_ERROR),
            ).toBeInTheDocument();
            expect(screen.getByText(TYPE_CD_EMPTY_ERROR)).toBeInTheDocument();
            expect(screen.getByText(AMOUNT_EMPTY_ERROR)).toBeInTheDocument();

            // The confirm gate stays shut and no write is attempted.
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });

        it('blocks submit on an invalid amount', async () => {
            const user = RenderAddPage();

            // '100' is non-empty but fails the signed fixed-width money format.
            await FillTransactionForm(user, MakeTransactionCreate({ tran_amt: '100' }));
            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            expect(
                await screen.findByText(AMOUNT_FORMAT_ERROR),
            ).toBeInTheDocument();
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });
    });

    // ----------------------------------------------------------------------
    // 3. account-OR-card key rule (COTRN02C VALIDATE-INPUT-KEY-FIELDS)
    // ----------------------------------------------------------------------

    describe('account-or-card key rule', () => {
        it('requires an account or a card number when neither is entered', async () => {
            const user = RenderAddPage();

            await FillTransactionForm(
                user,
                MakeTransactionCreate({ acct_id: '', card_num: '' }),
            );
            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            expect(
                await screen.findByText(ACCT_OR_CARD_REQUIRED_ERROR),
            ).toBeInTheDocument();
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });

        it('passes the key rule when only the card number is provided', async () => {
            const user = RenderAddPage();

            await FillTransactionForm(
                user,
                MakeTransactionCreate({ acct_id: '', card_num: VALID_CARD_NUM }),
            );
            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            // Passing validation opens the confirm dialog; the key error is absent.
            expect(await screen.findByRole('dialog')).toBeInTheDocument();
            expect(
                screen.queryByText(ACCT_OR_CARD_REQUIRED_ERROR),
            ).not.toBeInTheDocument();
        });
    });

    // ----------------------------------------------------------------------
    // 4. Confirm-dialog gate — AddTransaction only fires AFTER confirm
    // ----------------------------------------------------------------------

    describe('confirm-dialog gate', () => {
        it('opens the confirm dialog and posts only after the user confirms', async () => {
            const user = RenderAddPage();
            jest.mocked(TransactionsApi.AddTransaction).mockResolvedValueOnce(
                MakeTransactionRead(),
            );

            await FillTransactionForm(user, MakeTransactionCreate());
            const dialog = await OpenConfirmDialog(user);

            // The dialog is the primary-colored, titled confirmation prompt.
            expect(dialog).toHaveAccessibleName(PAGE_HEADING);
            expect(within(dialog).getByText(CONFIRM_MESSAGE)).toBeInTheDocument();
            const confirmButton = within(dialog).getByRole('button', {
                name: BUTTON_ADD,
            });
            expect(confirmButton.className).toMatch(
                /colorPrimary|containedPrimary/i,
            );

            // The gate holds: nothing is posted until the confirm button fires.
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();

            await user.click(confirmButton);

            await waitFor(() =>
                expect(TransactionsApi.AddTransaction).toHaveBeenCalledWith(
                    expect.objectContaining(MakeTransactionCreate()),
                ),
            );
            expect(TransactionsApi.AddTransaction).toHaveBeenCalledTimes(1);
        });
    });

    // ----------------------------------------------------------------------
    // 5. Successful add (201) — success alert + form reset
    // ----------------------------------------------------------------------

    describe('successful add', () => {
        it('shows a success alert and clears the form on 201', async () => {
            const user = RenderAddPage();
            const createdRead = MakeTransactionRead({ tran_id: DEFAULT_TRAN_ID });
            jest.mocked(TransactionsApi.AddTransaction).mockResolvedValueOnce(
                createdRead,
            );

            await FillTransactionForm(user, MakeTransactionCreate());
            const dialog = await OpenConfirmDialog(user);
            await user.click(within(dialog).getByRole('button', { name: BUTTON_ADD }));

            const alert = await screen.findByRole('alert');
            expect(alert).toHaveTextContent(SUCCESS_MESSAGE);
            expect(alert).toHaveTextContent(createdRead.tran_id);
            // MUI applies `MuiAlert-filledSuccess` for the success severity.
            expect(alert.className).toMatch(/success/i);

            // The page resets the form after a successful add.
            await waitFor(() =>
                expect(screen.getByLabelText('Enter Acct #:')).toHaveValue(''),
            );
            expect(screen.getByLabelText('Amount:')).toHaveValue('');
        });
    });

    // ----------------------------------------------------------------------
    // 6. CRITICAL — posting code 102 OVERLIMIT surfaced via ErrorAlert
    // ----------------------------------------------------------------------

    describe('posting code 102 (overlimit)', () => {
        it('surfaces the 102 message, does not navigate, and retains input', async () => {
            const user = RenderAddPage();
            const overlimitError = new ApiError({
                status: 400,
                code: '102',
                message: 'OVERLIMIT TRANSACTION',
            });
            jest.mocked(TransactionsApi.AddTransaction).mockRejectedValueOnce(
                overlimitError,
            );

            await FillTransactionForm(user, MakeTransactionCreate());
            const dialog = await OpenConfirmDialog(user);
            await user.click(within(dialog).getByRole('button', { name: BUTTON_ADD }));

            const alert = await screen.findByRole('alert');
            expect(alert).toHaveTextContent(/overlimit/i);
            expect(alert).toHaveTextContent('[102]');
            expect(alert.className).toMatch(/error/i);

            // A rejected posting neither navigates away nor clears the entered data.
            expect(mockPush).not.toHaveBeenCalled();
            expect(screen.getByLabelText('Enter Acct #:')).toHaveValue(VALID_ACCT_ID);
        });
    });

    // ----------------------------------------------------------------------
    // 7. All five posting codes (100/101/102/103/109) mapped via ErrorAlert
    // ----------------------------------------------------------------------

    describe('posting-code surfacing', () => {
        it.each(POSTING_CODE_CASES)(
            'displays the mapped message for code %s',
            async (code, message, messagePattern) => {
                const user = RenderAddPage();
                jest.mocked(TransactionsApi.AddTransaction).mockRejectedValueOnce(
                    new ApiError({ status: 400, code, message }),
                );

                await FillTransactionForm(user, MakeTransactionCreate());
                const dialog = await OpenConfirmDialog(user);
                await user.click(
                    within(dialog).getByRole('button', { name: BUTTON_ADD }),
                );

                const alert = await screen.findByRole('alert');
                expect(alert).toHaveTextContent(messagePattern);
                expect(alert).toHaveTextContent(`[${code}]`);
                expect(alert.className).toMatch(/error/i);
            },
        );
    });

    // ----------------------------------------------------------------------
    // 8. Cancel in the confirm dialog — no POST, dialog closes
    // ----------------------------------------------------------------------

    describe('confirm-dialog cancel', () => {
        it('does not post and closes the dialog when cancelled', async () => {
            const user = RenderAddPage();

            await FillTransactionForm(user, MakeTransactionCreate());
            const dialog = await OpenConfirmDialog(user);
            await user.click(
                within(dialog).getByRole('button', { name: BUTTON_CANCEL }),
            );

            await waitFor(() =>
                expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
            );
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });
    });

    // ----------------------------------------------------------------------
    // 9. Amount / date regex acceptance and rejection
    // ----------------------------------------------------------------------

    describe('amount and date regexes', () => {
        it('accepts a valid signed amount and ISO date', async () => {
            const user = RenderAddPage();

            await FillTransactionForm(user, MakeTransactionCreate());
            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            // A clean form advances to the confirm gate with no format errors.
            expect(await screen.findByRole('dialog')).toBeInTheDocument();
            expect(screen.queryByText(AMOUNT_FORMAT_ERROR)).not.toBeInTheDocument();
            expect(
                screen.queryByText(ORIG_DATE_FORMAT_ERROR),
            ).not.toBeInTheDocument();
        });

        it('rejects an amount missing the sign and fixed width', async () => {
            const user = RenderAddPage();

            await FillTransactionForm(
                user,
                MakeTransactionCreate({ tran_amt: '100.00' }),
            );
            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            expect(
                await screen.findByText(AMOUNT_FORMAT_ERROR),
            ).toBeInTheDocument();
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });

        it('rejects a non-ISO orig date', async () => {
            const user = RenderAddPage();

            await FillTransactionForm(
                user,
                MakeTransactionCreate({ orig_ts: '01/15/2024' }),
            );
            await user.click(screen.getByRole('button', { name: BUTTON_ADD }));

            expect(
                await screen.findByText(ORIG_DATE_FORMAT_ERROR),
            ).toBeInTheDocument();
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(TransactionsApi.AddTransaction).not.toHaveBeenCalled();
        });
    });
});
