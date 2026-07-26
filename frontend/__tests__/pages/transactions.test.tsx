/** Transaction List page spec — legacy origin BMS COTRN00 / Tx CT00 / program COTRN00C. */

/*
 * Component / integration spec for the modern Transaction List page
 * (`@/app/transactions/page`, default export `TransactionsPage`) — the
 * redesigned (Material Design 3 / MUI) replacement for the legacy 3270 browse
 * map COTRN00 (CICS transaction CT00, COBOL program COTRN00C).
 *
 * The REAL page is rendered against a mocked `@/lib/apiClient` (only
 * `TransactionsApi` is replaced; `ApiError` stays real) and a mocked
 * `next/navigation` router — there is NO real network and NO real navigation.
 *
 * Behavioral contracts asserted here (all verified against the real
 * `transactions/page.tsx` + `DataTable.tsx`):
 *   - the page heading is 'List Transactions';
 *   - the list API is pagination-only: `ListTransactions({ page, page_size })`
 *     with `page_size === 7` (F-004), never a filter object;
 *   - `card_num` is rendered MASKED (the full PAN never reaches the DOM);
 *   - `orig_ts` is shown date-only (leading 10 chars);
 *   - `tran_amt` is rendered VERBATIM as its exact decimal string (no numeric
 *     reformat, no thousands separators);
 *   - clicking a row navigates to the encoded detail route
 *     `/transactions/view?tranId=<encoded id>`;
 *   - the Search Tran ID field is numeric-only (COTRN00C fidelity) and never
 *     injects a filter into the list API;
 *   - advancing pagination refetches the next page;
 *   - empty pages and API errors are surfaced (empty message + error alert).
 */

import TransactionsPage from '@/app/transactions/page';
import { useRouter } from 'next/navigation';

import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    SetupUser,
    MakePaginatedResponse,
} from '../testUtils';
import { TransactionsApi, ApiError } from '@/lib/apiClient';
import type { TransactionSummary, PaginatedResponse } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module mocks.                                                             */
/*                                                                           */
/* Each factory is SELF-CONTAINED (it references no outer variable) to avoid  */
/* the temporal-dead-zone error jest raises when a hoisted `jest.mock`        */
/* factory reaches a not-yet-initialized module binding. The router spy       */
/* functions live at module scope and are wired into `useRouter` inside       */
/* `beforeEach`, which runs long after module initialization.                 */
/* ------------------------------------------------------------------------- */

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: jest.fn(),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/transactions',
}));

// Keep the real apiClient module (ApiError, IsApiError, the axios singleton)
// and replace ONLY the TransactionsApi resource wrapper with jest spies.
jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    TransactionsApi: {
        ListTransactions: jest.fn(),
        GetTransaction: jest.fn(),
        AddTransaction: jest.fn(),
    },
}));

/* ------------------------------------------------------------------------- */
/* Router spies (mock*-prefixed; referenced only at runtime in beforeEach).   */
/* ------------------------------------------------------------------------- */

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();

/* ------------------------------------------------------------------------- */
/* Test constants (Ochs rule: ALL_UPPERCASE with underscores).               */
/* ------------------------------------------------------------------------- */

/** Page heading rendered by TransactionsPage (legacy COTRN00 map title). */
const PAGE_TITLE = 'List Transactions';

/** Empty-grid message the page passes to DataTable (overrides its default). */
const EMPTY_TRANSACTIONS_MESSAGE = 'No transactions found.';

/** Inline validation message shown when Search Tran ID is not numeric. */
const TRAN_ID_NUMERIC_ERROR = 'Tran ID must be Numeric.';

/** Server-driven page size — the legacy F-004 browse limit (DEFAULT_PAGE_SIZE). */
const EXPECTED_PAGE_SIZE = 7;

/** Detail-view route the page navigates to (carries the id as `tranId`). */
const TRANSACTION_VIEW_ROUTE = '/transactions/view';

/** Accessible label of the search text field (FormField `label` prop). */
const SEARCH_FIELD_LABEL = 'Search Tran ID';

/** Accessible name of the search submit button. */
const SEARCH_BUTTON_NAME = 'Search';

/** A full (unmasked) primary account number, used to prove masking. */
const FULL_CARD_NUMBER = '4111111111111111';

/** The masked form the page must render (only the last four digits visible). */
const MASKED_CARD_NUMBER = '************1111';

/** Message carried by the simulated backend failure in the error scenario. */
const SERVER_ERROR_MESSAGE = 'Internal server error.';

/** First-row transaction id used across the row-oriented scenarios. */
const FIRST_TRAN_ID = '0000000000000001';

/** Second-row transaction id used for multi-row / pagination scenarios. */
const SECOND_TRAN_ID = '0000000000000002';

/** A valid numeric Tran ID entered to prove the search accepts numeric input. */
const NUMERIC_SEARCH_ID = '123';

/** Column headers rendered by the grid, in order (verbatim from the page). */
const TRANSACTION_COLUMN_HEADERS: readonly string[] = [
    'Transaction ID',
    'Date',
    'Card Number',
    'Type',
    'Category',
    'Source',
    'Description',
    'Amount',
];

/* ------------------------------------------------------------------------- */
/* Fixtures & helpers (PascalCase helpers per the Ochs Rule).                 */
/* ------------------------------------------------------------------------- */

/**
 * Build a `TransactionSummary` list row. Every field is a string (ids keep
 * their leading zeros; `tran_amt` is an exact decimal STRING). The summary DTO
 * carries a nullable `tran_desc`, surfaced by the grid's Description column.
 *
 * @param overrides - Partial fields that replace any summary default.
 * @returns A fully-populated `TransactionSummary`.
 */
function MakeTransactionSummary(
    overrides: Partial<TransactionSummary> = {},
): TransactionSummary {
    const baseTransaction: TransactionSummary = {
        tran_id: FIRST_TRAN_ID,
        card_num: FULL_CARD_NUMBER,
        tran_type_cd: '01',
        tran_cat_cd: '0005',
        tran_amt: '-100.00',
        tran_source: 'POS',
        tran_desc: 'Purchase at Abshire-Lowe',
        orig_ts: '2024-01-15-12.30.00.000000',
    };

    return { ...baseTransaction, ...overrides };
}

/**
 * Arm the next `TransactionsApi.ListTransactions` call to resolve with a single
 * paginated page built from `items`. Uses `mockResolvedValueOnce` so a scenario
 * can queue distinct pages for successive fetches (e.g. pagination advance).
 *
 * @param items - The transaction rows contained on the page.
 * @param overrides - Partial envelope fields (page flags, totals).
 */
function ArmListResponse(
    items: TransactionSummary[],
    overrides?: Partial<PaginatedResponse<TransactionSummary>>,
): void {
    const listMock = TransactionsApi.ListTransactions as jest.Mock;
    listMock.mockResolvedValueOnce(
        MakePaginatedResponse<TransactionSummary>(items, overrides),
    );
}

/**
 * Return only the table BODY rows (excludes the header row) by scoping the
 * query to the last MUI `rowgroup` (the `<tbody>`).
 *
 * @returns The rendered body rows.
 */
function GetTableBodyRows(): HTMLElement[] {
    const rowGroups = screen.getAllByRole('rowgroup');
    const tableBody = rowGroups[rowGroups.length - 1];
    return within(tableBody).getAllByRole('row');
}

/** Render the real page inside the shared MUI theme provider. */
function RenderTransactionsPage(): ReturnType<typeof RenderWithProviders> {
    return RenderWithProviders(<TransactionsPage />);
}

describe('TransactionsPage', () => {
    beforeEach(() => {
        // `clearMocks` (global) wipes call history; resetting the API spies also
        // drops any queued once-implementations. Then (re)point `useRouter` at
        // the module-scope spies for this test.
        (TransactionsApi.ListTransactions as jest.Mock).mockReset();
        (TransactionsApi.GetTransaction as jest.Mock).mockReset();
        (TransactionsApi.AddTransaction as jest.Mock).mockReset();

        (useRouter as jest.Mock).mockReturnValue({
            push: mockPush,
            replace: mockReplace,
            back: mockBack,
            refresh: mockRefresh,
            prefetch: mockPrefetch,
        });
    });

    // --- Scenario 1: list renders + title + headers + pagination-only params.
    describe('list rendering', () => {
        it('renders the title, headers, and one row per transaction, fetching page 1 at page size 7', async () => {
            ArmListResponse([
                MakeTransactionSummary(),
                MakeTransactionSummary({
                    tran_id: SECOND_TRAN_ID,
                    tran_desc: 'Refund from Kihn Group',
                }),
            ]);

            RenderTransactionsPage();

            expect(
                screen.getByRole('heading', { name: PAGE_TITLE }),
            ).toBeInTheDocument();

            // Rows arrive asynchronously once the mount-time fetch resolves.
            expect(await screen.findByText(FIRST_TRAN_ID)).toBeInTheDocument();
            expect(screen.getByText(SECOND_TRAN_ID)).toBeInTheDocument();
            expect(GetTableBodyRows()).toHaveLength(2);

            // Column headers are taken verbatim from transactions/page.tsx.
            for (const headerLabel of TRANSACTION_COLUMN_HEADERS) {
                expect(
                    screen.getByRole('columnheader', { name: headerLabel }),
                ).toBeInTheDocument();
            }
            expect(screen.getAllByRole('columnheader')).toHaveLength(
                TRANSACTION_COLUMN_HEADERS.length,
            );

            // dest QA F-2 — the Description column surfaces each row's tran_desc
            // (legacy COTRN00 TDESC01-07), matching the COTRN01 detail view.
            expect(
                screen.getByText('Purchase at Abshire-Lowe'),
            ).toBeInTheDocument();
            expect(
                screen.getByText('Refund from Kihn Group'),
            ).toBeInTheDocument();

            // The list API is pagination-only (no filter beyond page/page_size).
            await waitFor(() => {
                expect(TransactionsApi.ListTransactions).toHaveBeenCalledWith({
                    page: 1,
                    page_size: EXPECTED_PAGE_SIZE,
                });
            });
            expect(TransactionsApi.ListTransactions).toHaveBeenCalledTimes(1);
        });
    });

    // --- Scenario 2: card_num masked.
    describe('sensitive-data masking', () => {
        it('masks card_num so the full PAN is never rendered', async () => {
            ArmListResponse([
                MakeTransactionSummary({ card_num: FULL_CARD_NUMBER }),
            ]);

            RenderTransactionsPage();

            expect(
                await screen.findByText(MASKED_CARD_NUMBER),
            ).toBeInTheDocument();
            expect(
                screen.queryByText(FULL_CARD_NUMBER),
            ).not.toBeInTheDocument();
        });
    });

    // --- Scenario 3: orig_ts date-only (slice 0,10).
    describe('date formatting', () => {
        it('shows only the date portion of orig_ts (leading 10 chars)', async () => {
            ArmListResponse([
                MakeTransactionSummary({
                    orig_ts: '2024-01-15-12.30.00.000000',
                }),
            ]);

            RenderTransactionsPage();

            expect(await screen.findByText('2024-01-15')).toBeInTheDocument();
            // The full timestamp (and its time tail) must not be rendered.
            expect(
                screen.queryByText('2024-01-15-12.30.00.000000'),
            ).not.toBeInTheDocument();
            expect(screen.queryByText(/12\.30\.00/)).not.toBeInTheDocument();
        });
    });

    // --- Scenario 4: tran_amt verbatim.
    describe('amount rendering', () => {
        it('renders tran_amt verbatim (no numeric reformat, no separators)', async () => {
            ArmListResponse([
                MakeTransactionSummary({
                    tran_id: FIRST_TRAN_ID,
                    tran_amt: '-100.00',
                }),
                MakeTransactionSummary({
                    tran_id: SECOND_TRAN_ID,
                    tran_amt: '2500.50',
                }),
            ]);

            RenderTransactionsPage();

            // Signed, 2-decimal value shown with the shared '$' currency prefix
            // (QA Issue 1 — consistent money presentation via FormatMoney), the
            // decimal preserved exactly (no numeric coercion).
            expect(await screen.findByText('$-100.00')).toBeInTheDocument();
            expect(screen.getByText('$2500.50')).toBeInTheDocument();

            // Numeric coercion would drop trailing zeros ('-100') — must NOT happen.
            expect(screen.queryByText('-100')).not.toBeInTheDocument();
            // A locale formatter would insert a thousands separator — must NOT happen.
            expect(screen.queryByText('2,500.50')).not.toBeInTheDocument();
        });
    });

    // --- Scenario 5: row click navigates with an encoded tranId.
    describe('row navigation', () => {
        it('navigates to the encoded detail route when a row is clicked', async () => {
            const user = SetupUser();
            ArmListResponse([
                MakeTransactionSummary({ tran_id: FIRST_TRAN_ID }),
            ]);

            RenderTransactionsPage();

            await user.click(await screen.findByText(FIRST_TRAN_ID));

            expect(mockPush).toHaveBeenCalledWith(
                `${TRANSACTION_VIEW_ROUTE}?tranId=${encodeURIComponent(
                    FIRST_TRAN_ID,
                )}`,
            );
        });
    });

    // --- Scenario 6: numeric-only Search Tran ID validation.
    describe('search validation', () => {
        it('rejects a non-numeric Tran ID, never filters the list API, and accepts a numeric id', async () => {
            const user = SetupUser();
            ArmListResponse([
                MakeTransactionSummary({ tran_id: FIRST_TRAN_ID }),
            ]);

            RenderTransactionsPage();
            // Wait for the initial (mount) page load to settle.
            await screen.findByText(FIRST_TRAN_ID);

            const searchField = screen.getByRole('textbox', {
                name: SEARCH_FIELD_LABEL,
            });
            const searchButton = screen.getByRole('button', {
                name: SEARCH_BUTTON_NAME,
            });

            // Non-numeric entry → inline error, no navigation.
            await user.type(searchField, 'ABC');
            await user.click(searchButton);

            expect(
                screen.getByText(TRAN_ID_NUMERIC_ERROR),
            ).toBeInTheDocument();
            expect(mockPush).not.toHaveBeenCalled();

            // The list endpoint was called ONLY for the mount page (paging only),
            // never with a search filter.
            expect(TransactionsApi.ListTransactions).toHaveBeenCalledTimes(1);
            expect(TransactionsApi.ListTransactions).toHaveBeenCalledWith({
                page: 1,
                page_size: EXPECTED_PAGE_SIZE,
            });

            // Numeric entry → validation passes and navigates to the detail view.
            await user.clear(searchField);
            await user.type(searchField, NUMERIC_SEARCH_ID);
            await user.click(searchButton);

            expect(mockPush).toHaveBeenCalledWith(
                `${TRANSACTION_VIEW_ROUTE}?tranId=${encodeURIComponent(
                    NUMERIC_SEARCH_ID,
                )}`,
            );
            expect(
                screen.queryByText(TRAN_ID_NUMERIC_ERROR),
            ).not.toBeInTheDocument();
            // Search navigates; it does not re-list — still exactly one fetch.
            expect(TransactionsApi.ListTransactions).toHaveBeenCalledTimes(1);
        });
    });

    // --- Scenario 7: pagination refetch.
    describe('pagination', () => {
        it('refetches the next page when pagination advances', async () => {
            const user = SetupUser();
            ArmListResponse([MakeTransactionSummary({ tran_id: FIRST_TRAN_ID })], {
                page: 1,
                total_items: 8,
                total_pages: 2,
                has_next: true,
                has_previous: false,
            });
            ArmListResponse([MakeTransactionSummary({ tran_id: SECOND_TRAN_ID })], {
                page: 2,
                total_items: 8,
                total_pages: 2,
                has_next: false,
                has_previous: true,
            });

            RenderTransactionsPage();
            await screen.findByText(FIRST_TRAN_ID);

            await user.click(
                screen.getByRole('button', { name: /go to page 2/i }),
            );

            await waitFor(() => {
                expect(TransactionsApi.ListTransactions).toHaveBeenCalledWith({
                    page: 2,
                    page_size: EXPECTED_PAGE_SIZE,
                });
            });
            expect(await screen.findByText(SECOND_TRAN_ID)).toBeInTheDocument();
        });
    });

    // --- Scenario 8: empty page + API error.
    describe('empty and error states', () => {
        it('shows the empty message when the page has no transactions', async () => {
            ArmListResponse([], { total_items: 0, total_pages: 0 });

            RenderTransactionsPage();

            expect(
                await screen.findByText(EMPTY_TRANSACTIONS_MESSAGE),
            ).toBeInTheDocument();
        });

        it('surfaces an ApiError through the error alert', async () => {
            const listMock = TransactionsApi.ListTransactions as jest.Mock;
            listMock.mockRejectedValueOnce(
                new ApiError({ status: 500, message: SERVER_ERROR_MESSAGE }),
            );

            RenderTransactionsPage();

            const errorAlert = await screen.findByRole('alert');
            expect(errorAlert).toHaveTextContent(SERVER_ERROR_MESSAGE);
        });
    });

    // --- Scenario 9: request-generation guard (QA M-05).
    describe('stale response handling (M-05)', () => {
        it('discards a superseded page fetch so a slow earlier page cannot overwrite a newer one', async () => {
            const user = SetupUser();
            const THIRD_TRAN_ID = '0000000000000003';

            // Mount (page 1) resolves immediately with a 3-page envelope so the
            // pagination control renders; the page-2 and page-3 fetches are
            // hand-controlled (deferred) so the spec dictates resolution ORDER.
            let resolveStalePageTwo!: (
                value: PaginatedResponse<TransactionSummary>,
            ) => void;
            let resolveFreshPageThree!: (
                value: PaginatedResponse<TransactionSummary>,
            ) => void;
            const stalePageTwo = new Promise<
                PaginatedResponse<TransactionSummary>
            >((resolve) => {
                resolveStalePageTwo = resolve;
            });
            const freshPageThree = new Promise<
                PaginatedResponse<TransactionSummary>
            >((resolve) => {
                resolveFreshPageThree = resolve;
            });

            const listMock = TransactionsApi.ListTransactions as jest.Mock;
            listMock
                .mockResolvedValueOnce(
                    MakePaginatedResponse<TransactionSummary>(
                        [MakeTransactionSummary({ tran_id: FIRST_TRAN_ID })],
                        { page: 1, total_items: 21, total_pages: 3, has_next: true },
                    ),
                ) // mount -> generation 1 (page 1)
                .mockReturnValueOnce(stalePageTwo) // page 2 -> older generation
                .mockReturnValueOnce(freshPageThree); // page 3 -> newer generation

            RenderTransactionsPage();
            await screen.findByText(FIRST_TRAN_ID);

            // Advance to page 2 (older request, left in flight), then immediately
            // to page 3 (newer request). The grid keeps showing page 1 until a
            // fetch resolves, so both page buttons stay clickable.
            await user.click(
                screen.getByRole('button', { name: /go to page 2/i }),
            );
            await user.click(
                screen.getByRole('button', { name: /go to page 3/i }),
            );
            await waitFor(() =>
                expect(listMock).toHaveBeenCalledTimes(3),
            );

            // Resolve the NEWER (page 3) request first; its row must render.
            resolveFreshPageThree(
                MakePaginatedResponse<TransactionSummary>(
                    [MakeTransactionSummary({ tran_id: THIRD_TRAN_ID })],
                    { page: 3, total_items: 21, total_pages: 3, has_previous: true },
                ),
            );
            expect(await screen.findByText(THIRD_TRAN_ID)).toBeInTheDocument();

            // Now resolve the OLDER (page 2) request. The generation guard must
            // discard it: the page-3 row stays and the page-2 row never appears.
            resolveStalePageTwo(
                MakePaginatedResponse<TransactionSummary>(
                    [MakeTransactionSummary({ tran_id: SECOND_TRAN_ID })],
                    {
                        page: 2,
                        total_items: 21,
                        total_pages: 3,
                        has_next: true,
                        has_previous: true,
                    },
                ),
            );
            await waitFor(() =>
                expect(screen.getByText(THIRD_TRAN_ID)).toBeInTheDocument(),
            );
            expect(screen.queryByText(SECOND_TRAN_ID)).not.toBeInTheDocument();
        });
    });
});
