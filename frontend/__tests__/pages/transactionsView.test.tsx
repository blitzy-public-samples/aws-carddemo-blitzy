/** Transaction View page spec — legacy origin BMS COTRN01 / Tx CT01 / program COTRN01C. Read-only. */

/*
 * Component / integration spec for the modern Transaction View page
 * (frontend/src/app/transactions/view/page.tsx) — the redesigned replacement
 * for the legacy 3270 screen BMS map COTRN01 (CICS transaction CT01, COBOL
 * program COTRN01C). The REAL page is rendered inside the application MUI theme;
 * the only collaborators replaced are:
 *   - `next/navigation` — so the `tranId` search param and the router are fully
 *     controllable (the default export wraps its content in <Suspense> and reads
 *     `useSearchParams().get('tranId')`); and
 *   - the `TransactionsApi` resource object — so `GetTransaction` is a jest fn
 *     and NO real network call is ever made.
 * `ApiError` / `IsApiError` are kept REAL (via requireActual) so the page's
 * ErrorAlert normalizes a thrown ApiError exactly as it does at runtime. The
 * page is NOT role-gated, so `@/lib/auth` is deliberately NOT mocked.
 * Greenfield test — no legacy test origin.
 */

/* --------------------------------------------------------------------------- */
/* next/navigation mock (hoisted): controlled useSearchParams + router spies.  */
/* Module-scope vars referenced inside the hoisted factory MUST be prefixed    */
/* `mock` so Jest's out-of-scope-variable guard permits them; the factory only */
/* builds closures, so the vars are read lazily at render time (after init).   */
/* --------------------------------------------------------------------------- */

let mockSearchParams = new URLSearchParams();
const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();

jest.mock('next/navigation', () => ({
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        back: mockBack,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
    }),
    useSearchParams: () => mockSearchParams,
    usePathname: () => '/transactions/view',
}));

/* --------------------------------------------------------------------------- */
/* @/lib/apiClient mock (hoisted): stub ONLY the TransactionsApi resource, keep */
/* every other export (notably ApiError / IsApiError) as its real value so the  */
/* page's error path behaves exactly as in production.                          */
/* --------------------------------------------------------------------------- */

jest.mock('@/lib/apiClient', () => {
    const actual =
        jest.requireActual<typeof import('@/lib/apiClient')>('@/lib/apiClient');
    return {
        __esModule: true,
        ...actual,
        TransactionsApi: {
            ListTransactions: jest.fn(),
            GetTransaction: jest.fn(),
            AddTransaction: jest.fn(),
        },
    };
});

import TransactionsViewPage from '@/app/transactions/view/page';
import { RenderWithProviders, screen, waitFor, SetupUser } from '../testUtils';
import { TransactionsApi, ApiError } from '@/lib/apiClient';

import type { TransactionRead } from '@/types';

/* --------------------------------------------------------------------------- */
/* Constants (Ochs rule: ALL_UPPERCASE with underscores).                      */
/* --------------------------------------------------------------------------- */

/** Zero-padded deep-link Tran ID used by the auto-fetch scenarios (X(16)). */
const DEFAULT_TRAN_ID = '0000000000000001';

/** Empty-Tran-ID validation message — must match COTRN01C via the page. */
const TRAN_ID_EMPTY_ERROR = 'Tran ID can NOT be empty...';

/** Full (unmasked) PAN carried by the fixture — must NEVER reach the DOM. */
const FIXTURE_FULL_PAN = '4111111111111111';

/** Masked PAN the page renders (12 mask chars + last 4) via MaskCardNumber. */
const EXPECTED_MASKED_PAN = '************1111';

/** Signed decimal amount — must render verbatim (never numeric-coerced). */
const FIXTURE_TRAN_AMT = '-100.00';

/**
 * The amount as displayed on screen: the raw Decimal prefixed with the shared
 * '$' currency symbol (QA Issue 1 — the detail view now renders monetary values
 * through {@link FormatMoney}, matching accounts-view / bill-pay). The decimal
 * is still preserved verbatim behind the prefix (no numeric coercion).
 */
const FIXTURE_TRAN_AMT_DISPLAY = '$-100.00';

/** Full 26-char origination timestamp; the page slices it to YYYY-MM-DD. */
const FIXTURE_ORIG_TS = '2024-01-15-12.30.00.000000';

/** Full 26-char processing timestamp; the page slices it to YYYY-MM-DD. */
const FIXTURE_PROC_TS = '2024-01-16-08.00.00.000000';

/** Date-only prefix expected from FIXTURE_ORIG_TS.slice(0, 10). */
const EXPECTED_ORIG_DATE = '2024-01-15';

/** Date-only prefix expected from FIXTURE_PROC_TS.slice(0, 10). */
const EXPECTED_PROC_DATE = '2024-01-16';

/** Accessible name of the Tran ID lookup field (FormField label="Tran ID"). */
const TRAN_ID_FIELD_LABEL = 'Tran ID';

/** Accessible name of the manual-lookup Fetch button. */
const FETCH_BUTTON_NAME = 'Fetch';

/** Heading rendered by the detail Card once a transaction is loaded. */
const DETAIL_CARD_TITLE = 'Transaction Detail';

/** Every read-only detail label, verbatim from COTRN01.bms (all 13 fields). */
const DETAIL_FIELD_LABELS: readonly string[] = [
    'Transaction ID:',
    'Card Number:',
    'Type CD:',
    'Category CD:',
    'Source:',
    'Description:',
    'Amount:',
    'Orig Date:',
    'Proc Date:',
    'Merchant ID:',
    'Merchant Name:',
    'Merchant City:',
    'Merchant Zip:',
];

/* --------------------------------------------------------------------------- */
/* Typed handle to the mocked collaborator. `clearMocks: true` clears its call */
/* history before every test but never replaces the fn object, so this stays   */
/* valid across the whole suite.                                               */
/* --------------------------------------------------------------------------- */

const mockGetTransaction = jest.mocked(TransactionsApi.GetTransaction);

/* --------------------------------------------------------------------------- */
/* Fixture factory (PascalCase per Ochs rule): all 13 TransactionRead fields   */
/* as strings, each overridable through a single object param (<= 4 params).   */
/* --------------------------------------------------------------------------- */

/**
 * Build a fully-populated `TransactionRead` (all 13 fields, every value a
 * string — ids preserve leading zeros and amounts are exact decimals).
 *
 * @param overrides - Partial fields that replace any default.
 * @returns A valid `TransactionRead` fixture.
 */
function MakeTransactionRead(
    overrides?: Partial<TransactionRead>,
): TransactionRead {
    const baseTransaction: TransactionRead = {
        tran_id: DEFAULT_TRAN_ID,
        tran_type_cd: '01',
        tran_cat_cd: '0005',
        tran_source: 'POS',
        tran_desc: 'GROCERY STORE PURCHASE',
        tran_amt: FIXTURE_TRAN_AMT,
        merchant_id: '000123456',
        merchant_name: 'ACME SUPERMARKET',
        merchant_city: 'SEATTLE',
        merchant_zip: '98101',
        card_num: FIXTURE_FULL_PAN,
        orig_ts: FIXTURE_ORIG_TS,
        proc_ts: FIXTURE_PROC_TS,
    };

    return { ...baseTransaction, ...overrides };
}

/* --------------------------------------------------------------------------- */
/* Reset the controlled search params before each test so the default path     */
/* deep-links with a valid tranId (auto-fetch). Individual tests override this  */
/* to an empty URLSearchParams to exercise the manual / validation paths.       */
/* --------------------------------------------------------------------------- */

beforeEach(() => {
    mockSearchParams = new URLSearchParams({ tranId: DEFAULT_TRAN_ID });
});

describe('TransactionsViewPage', () => {
    /* ----------------------------------------------------------------------- */
    /* 1. Auto-fetch on entry (arriving via deep-link ?tranId=...).            */
    /* ----------------------------------------------------------------------- */
    describe('auto-fetch on entry', () => {
        it('fetches by the tranId search param and renders the detail card', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            RenderWithProviders(<TransactionsViewPage />);

            await waitFor(() => {
                expect(mockGetTransaction).toHaveBeenCalledWith(DEFAULT_TRAN_ID);
            });
            expect(mockGetTransaction).toHaveBeenCalledTimes(1);

            expect(await screen.findByText(DETAIL_CARD_TITLE)).toBeInTheDocument();
            expect(screen.getByText(DEFAULT_TRAN_ID)).toBeInTheDocument();
        });

        it('exposes a single h1 page heading, an h2 section heading and a description list (N-01)', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            const { container } = RenderWithProviders(<TransactionsViewPage />);

            // Exactly one level-1 heading titles the page (was a bare
            // variant="h4" with no semantic level before the QA N-01 fix).
            const h1s = screen.getAllByRole('heading', { level: 1 });
            expect(h1s).toHaveLength(1);

            // The detail card's "Transaction Detail" title is a level-2 heading
            // beneath the page h1 (descending, non-skipping order).
            await screen.findByText(DETAIL_CARD_TITLE);
            expect(
                screen.getByRole('heading', {
                    level: 2,
                    name: DETAIL_CARD_TITLE,
                }),
            ).toBeInTheDocument();

            // The 13 read-only fields render as a description list, associating
            // every value with its label (dt -> dd) instead of two unlabeled
            // Typography runs. The transaction id value sits inside a <dd>.
            const descriptionList = container.querySelector('dl');
            expect(descriptionList).not.toBeNull();
            expect(
                descriptionList?.querySelectorAll('dt').length,
            ).toBe(DETAIL_FIELD_LABELS.length);
            expect(
                screen.getByText(DEFAULT_TRAN_ID).closest('dd'),
            ).not.toBeNull();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 2. Thirteen read-only fields render (no editable data inputs).          */
    /* ----------------------------------------------------------------------- */
    describe('read-only transaction detail', () => {
        it('renders all 13 field labels with a representative set of values', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            RenderWithProviders(<TransactionsViewPage />);

            expect(await screen.findByText(DETAIL_CARD_TITLE)).toBeInTheDocument();

            for (const label of DETAIL_FIELD_LABELS) {
                expect(screen.getByText(label)).toBeInTheDocument();
            }

            // Representative values (id, masked card, amount, both dates), each
            // rendered verbatim as read-only text.
            expect(screen.getByText(DEFAULT_TRAN_ID)).toBeInTheDocument();
            expect(screen.getByText(EXPECTED_MASKED_PAN)).toBeInTheDocument();
            expect(screen.getByText(FIXTURE_TRAN_AMT_DISPLAY)).toBeInTheDocument();
            expect(screen.getByText(EXPECTED_ORIG_DATE)).toBeInTheDocument();
            expect(screen.getByText(EXPECTED_PROC_DATE)).toBeInTheDocument();
        });

        it('exposes exactly one editable input (the Tran ID lookup); the detail is read-only text', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            RenderWithProviders(<TransactionsViewPage />);

            expect(await screen.findByText(DETAIL_CARD_TITLE)).toBeInTheDocument();

            // The 13 detail fields are Typography, never inputs; the SOLE textbox
            // on the page is the manual Tran ID lookup field.
            expect(screen.getAllByRole('textbox')).toHaveLength(1);
            expect(
                screen.getByRole('textbox', { name: TRAN_ID_FIELD_LABEL }),
            ).toBeInTheDocument();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 3. Card-number masking (AAP §0.7.8 — full PAN is never displayed).      */
    /* ----------------------------------------------------------------------- */
    describe('card number masking', () => {
        it('renders the masked PAN and never the full card number', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            RenderWithProviders(<TransactionsViewPage />);

            expect(await screen.findByText(EXPECTED_MASKED_PAN)).toBeInTheDocument();
            expect(screen.queryByText(FIXTURE_FULL_PAN)).not.toBeInTheDocument();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 4. Amounts render verbatim (exact decimal string, no numeric coercion). */
    /* ----------------------------------------------------------------------- */
    describe('amount rendering', () => {
        it('renders a negative signed decimal amount exactly as provided', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            RenderWithProviders(<TransactionsViewPage />);

            // Rendered with the shared '$' prefix (QA Issue 1); the signed
            // decimal is preserved verbatim behind it.
            expect(
                await screen.findByText(FIXTURE_TRAN_AMT_DISPLAY),
            ).toBeInTheDocument();
        });

        it('preserves trailing-zero precision without numeric coercion', async () => {
            mockGetTransaction.mockResolvedValueOnce(
                MakeTransactionRead({ tran_amt: '1234.50' }),
            );

            RenderWithProviders(<TransactionsViewPage />);

            // A coerced number would collapse to 1234.5; the exact string proves
            // the value is passed through untouched (behind the '$' prefix added
            // by FormatMoney for consistent presentation — QA Issue 1).
            expect(await screen.findByText('$1234.50')).toBeInTheDocument();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 5. Timestamps are sliced to their YYYY-MM-DD date prefix.               */
    /* ----------------------------------------------------------------------- */
    describe('timestamp date-only slicing', () => {
        it('renders orig_ts and proc_ts sliced to 10 chars, not the full value', async () => {
            mockGetTransaction.mockResolvedValueOnce(MakeTransactionRead());

            RenderWithProviders(<TransactionsViewPage />);

            expect(await screen.findByText(EXPECTED_ORIG_DATE)).toBeInTheDocument();
            expect(screen.getByText(EXPECTED_PROC_DATE)).toBeInTheDocument();

            // The full 26-char timestamps must NOT appear anywhere on screen.
            expect(screen.queryByText(FIXTURE_ORIG_TS)).not.toBeInTheDocument();
            expect(screen.queryByText(FIXTURE_PROC_TS)).not.toBeInTheDocument();
        });

        // Regression (QA #1 CRITICAL): proc_ts is null until a transaction is
        // posted. The detail screen must render an em-dash placeholder instead of
        // crashing with "Cannot read properties of null (reading 'length')".
        it('renders an em-dash placeholder for a null proc_ts without crashing', async () => {
            mockGetTransaction.mockResolvedValueOnce(
                MakeTransactionRead({ proc_ts: null }),
            );

            RenderWithProviders(<TransactionsViewPage />);

            // The detail still loads and the Orig Date still renders.
            expect(await screen.findByText(DETAIL_CARD_TITLE)).toBeInTheDocument();
            expect(screen.getByText(EXPECTED_ORIG_DATE)).toBeInTheDocument();

            // Proc Date shows the em-dash placeholder (\u2014), and no date is shown.
            expect(screen.getByText('\u2014')).toBeInTheDocument();
            expect(screen.queryByText(EXPECTED_PROC_DATE)).not.toBeInTheDocument();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 6. Empty Tran ID -> validation message shown, API NOT called.           */
    /* ----------------------------------------------------------------------- */
    describe('empty Tran ID validation', () => {
        it('shows TRAN_ID_EMPTY_ERROR and skips the API when fetching empty', async () => {
            mockSearchParams = new URLSearchParams(); // no tranId -> no auto-fetch
            const user = SetupUser();

            RenderWithProviders(<TransactionsViewPage />);

            await user.click(
                screen.getByRole('button', { name: FETCH_BUTTON_NAME }),
            );

            expect(
                await screen.findByText(TRAN_ID_EMPTY_ERROR),
            ).toBeInTheDocument();
            expect(mockGetTransaction).not.toHaveBeenCalled();
        });

        it('does not auto-fetch and renders no detail when no tranId param is present', () => {
            mockSearchParams = new URLSearchParams();

            RenderWithProviders(<TransactionsViewPage />);

            expect(mockGetTransaction).not.toHaveBeenCalled();
            expect(screen.queryByText(DETAIL_CARD_TITLE)).not.toBeInTheDocument();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 7. On-page manual lookup (Tran ID input + Fetch button).                */
    /* ----------------------------------------------------------------------- */
    describe('on-page manual lookup', () => {
        it('fetches the typed Tran ID and renders its detail', async () => {
            mockSearchParams = new URLSearchParams(); // deep-link off; manual path
            const lookupTranId = '0000000000000042';
            mockGetTransaction.mockResolvedValueOnce(
                MakeTransactionRead({ tran_id: lookupTranId }),
            );
            const user = SetupUser();

            RenderWithProviders(<TransactionsViewPage />);

            await user.type(
                screen.getByRole('textbox', { name: TRAN_ID_FIELD_LABEL }),
                lookupTranId,
            );
            await user.click(
                screen.getByRole('button', { name: FETCH_BUTTON_NAME }),
            );

            await waitFor(() => {
                expect(mockGetTransaction).toHaveBeenCalledWith(lookupTranId);
            });
            expect(await screen.findByText(lookupTranId)).toBeInTheDocument();
        });
    });

    /* ----------------------------------------------------------------------- */
    /* 8. API error / 404 -> an error alert surfaces; no detail card renders.  */
    /* ----------------------------------------------------------------------- */
    describe('API error handling', () => {
        it('surfaces an error alert when GetTransaction rejects with a 404 ApiError', async () => {
            const notFoundMessage = 'Transaction ID NOT found...';
            mockGetTransaction.mockRejectedValueOnce(
                new ApiError({ status: 404, message: notFoundMessage }),
            );

            RenderWithProviders(<TransactionsViewPage />);

            const alert = await screen.findByRole('alert');
            expect(alert).toHaveTextContent(notFoundMessage);
            expect(screen.queryByText(DETAIL_CARD_TITLE)).not.toBeInTheDocument();
        });
    });
});

