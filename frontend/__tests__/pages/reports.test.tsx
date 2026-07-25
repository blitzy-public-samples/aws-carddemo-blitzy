/** Reports page spec — legacy origin BMS CORPT00 / Tx CR00 / program CORPT00C. On-screen table + CSV/PDF download. */

/*
 * WHAT IS VERIFIED (nine scenarios, all against the REAL `@/app/reports/page`
 * rendered inside the app MUI theme):
 *   1. the report-type Select, date-range inputs, and CSV/PDF/Generate buttons
 *      all render;
 *   2. the Select exposes the Monthly/Yearly/Custom options wired to the runtime
 *      `ReportType` enum VALUES;
 *   3. Monthly/Yearly disable (auto-derive) the date inputs while Custom enables
 *      them;
 *   4. a Custom range with start > end is rejected with a validation message and
 *      NO API call, and start <= end is accepted;
 *   5. Generate calls `ReportsApi.GetTransactionReport` with `confirm: 'Y'` and
 *      renders the returned rows in a plain on-screen `<table>`;
 *   6. the CSV button calls `ReportsApi.DownloadTransactionReport(req, 'csv')`
 *      and triggers a Blob download;
 *   7. the PDF button calls `ReportsApi.DownloadTransactionReport(req, 'pdf')`
 *      and triggers a Blob download;
 *   8. totals and amounts render VERBATIM as Decimal strings (never Number /
 *      toFixed coerced — AAP Section 0.7.1);
 *   9. a thrown `ApiError` surfaces in an error alert.
 *
 * MOCKING: `next/navigation` is replaced defensively (the page does not consume
 * it, but its transitive shell might) and `@/lib/apiClient` keeps everything
 * real EXCEPT `ReportsApi` (so the real `ApiError` / `IsApiError` still power the
 * error path). There is NO real network. jsdom omits the Blob-download plumbing
 * (`URL.createObjectURL` / anchor `click`) so it is stubbed per test.
 *
 * Greenfield test infrastructure — no legacy test origin.
 */

/* --------------------------------------------------------------------------- */
/* next/navigation mock (mock*-prefixed fns so the hoisted factory may close    */
/* over them). The page itself imports no navigation hook; this is defensive.   */
/* --------------------------------------------------------------------------- */

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockRefresh = jest.fn();
const mockPrefetch = jest.fn();
const mockBack = jest.fn();
const mockForward = jest.fn();

jest.mock('next/navigation', () => ({
    __esModule: true,
    useRouter: () => ({
        push: mockPush,
        replace: mockReplace,
        refresh: mockRefresh,
        prefetch: mockPrefetch,
        back: mockBack,
        forward: mockForward,
    }),
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => '/reports',
    useParams: () => ({}),
}));

/* --------------------------------------------------------------------------- */
/* @/lib/apiClient mock: keep the module real (so `ApiError` and `IsApiError`    */
/* stay authentic for the error path) and replace ONLY `ReportsApi` with fns.   */
/* --------------------------------------------------------------------------- */

jest.mock('@/lib/apiClient', () => ({
    __esModule: true,
    ...jest.requireActual('@/lib/apiClient'),
    ReportsApi: {
        GetTransactionReport: jest.fn(),
        DownloadTransactionReport: jest.fn(),
    },
}));

import ReportsPage from '@/app/reports/page';
import {
    RenderWithProviders,
    screen,
    waitFor,
    within,
    fireEvent,
    SetupUser,
} from '../testUtils';
import { ReportsApi, ApiError } from '@/lib/apiClient';
import { ReportType } from '@/types';
import type { ReportResponse, TransactionReportRow } from '@/types';

/* --------------------------------------------------------------------------- */
/* Typed, mock-aware handles to the replaced ReportsApi methods.                */
/* --------------------------------------------------------------------------- */

const mockGetTransactionReport = jest.mocked(ReportsApi.GetTransactionReport);
const mockDownloadTransactionReport = jest.mocked(
    ReportsApi.DownloadTransactionReport,
);

/* --------------------------------------------------------------------------- */
/* Test constants (Ochs Test Rule: ALL_UPPERCASE with underscores). Labels are  */
/* taken VERBATIM from the real reports/page.tsx so the queries assert fidelity. */
/* --------------------------------------------------------------------------- */

/** Accessible-name matchers (regex tolerates the required '*' / selected-value suffix). */
const REPORT_TYPE_LABEL = /Report Type/i;
const START_DATE_LABEL = /Start Date/i;
const END_DATE_LABEL = /End Date/i;

/** Button accessible-name matchers. */
const GENERATE_BUTTON = /Generate/i;
const CSV_BUTTON = /Download CSV/i;
const PDF_BUTTON = /Download PDF/i;

/** Report-type option captions (exact REPORT_TYPE_OPTIONS labels from the page). */
const MONTHLY_OPTION = 'Monthly (Current Month)';
const YEARLY_OPTION = 'Yearly (Current Year)';
const CUSTOM_OPTION = 'Custom (Date Range)';

/** The results-table aria-label (page PAGE_TITLE) — a single plain MUI Table. */
const VALIDATION_ORDER_MESSAGE = 'Start date must be on or before the end date.';

/* --------------------------------------------------------------------------- */
/* Fixture factories (PascalCase per Ochs). Every money field is a verbatim     */
/* Decimal string — never a number (AAP Section 0.7.1).                         */
/* --------------------------------------------------------------------------- */

/**
 * Builds a single transaction report row. Mirrors the exact
 * {@link TransactionReportRow} shape from `@/types`.
 *
 * @param overrides - Partial fields that replace any row default.
 * @returns A fully-populated report row.
 */
function MakeReportRow(
    overrides?: Partial<TransactionReportRow>,
): TransactionReportRow {
    const baseRow: TransactionReportRow = {
        tran_id: '0000000000000001',
        acct_id: '00000000011',
        tran_type_cd: '01',
        tran_type_desc: 'PURCHASE',
        tran_cat_cd: '0005',
        tran_cat_desc: 'RETAIL PURCHASES',
        tran_source: 'POS',
        tran_amt: '-100.00',
    };

    return { ...baseRow, ...overrides };
}

/**
 * Builds a {@link ReportResponse} payload. Uses the REAL response shape
 * (`page_total` / `account_total` / `grand_total`), all as verbatim Decimal
 * strings.
 *
 * @param overrides - Partial fields that replace any response default.
 * @returns A fully-populated report response.
 */
function MakeReportResponse(
    overrides?: Partial<ReportResponse>,
): ReportResponse {
    const baseResponse: ReportResponse = {
        report_type: ReportType.Monthly,
        start_date: '2026-07-01',
        end_date: '2026-07-31',
        rows: [MakeReportRow()],
        page_total: '-100.00',
        account_total: '-100.00',
        grand_total: '-100.00',
    };

    return { ...baseResponse, ...overrides };
}

/* --------------------------------------------------------------------------- */
/* Interaction helpers (small, single-purpose — Ochs).                          */
/* --------------------------------------------------------------------------- */

/** A configured user-event session (the return type of {@link SetupUser}). */
type TestUser = ReturnType<typeof SetupUser>;

/**
 * Opens the report-type MUI Select and picks the option with the given caption.
 *
 * @param user - The active user-event session.
 * @param optionLabel - The visible option caption to click.
 */
async function SelectReportType(
    user: TestUser,
    optionLabel: string,
): Promise<void> {
    const combobox = screen.getByRole('combobox', { name: REPORT_TYPE_LABEL });
    await user.click(combobox);
    const option = await screen.findByRole('option', { name: optionLabel });
    await user.click(option);
}

/**
 * Arms `GetTransactionReport`, clicks Generate, and waits for the results table.
 * Used by the download specs because the CSV/PDF buttons stay disabled until a
 * report has been generated (`disabled={loading || !reportData}`).
 *
 * @param user - The active user-event session.
 * @param response - The report payload the API should resolve with.
 */
async function GenerateReport(
    user: TestUser,
    response: ReportResponse,
): Promise<void> {
    mockGetTransactionReport.mockResolvedValueOnce(response);
    await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));
    await screen.findByRole('table');
}

/* --------------------------------------------------------------------------- */
/* Blob-download plumbing stubs. jsdom does not implement                       */
/* URL.createObjectURL / revokeObjectURL, and an anchor click would log a       */
/* not-implemented navigation, so all three are stubbed per test.               */
/* --------------------------------------------------------------------------- */

let createObjectUrlMock: jest.Mock;
let revokeObjectUrlMock: jest.Mock;
let anchorClickSpy: jest.SpyInstance;

beforeEach(() => {
    createObjectUrlMock = jest.fn(() => 'blob:mock');
    revokeObjectUrlMock = jest.fn();
    window.URL.createObjectURL =
        createObjectUrlMock as unknown as typeof URL.createObjectURL;
    window.URL.revokeObjectURL =
        revokeObjectUrlMock as unknown as typeof URL.revokeObjectURL;
    anchorClickSpy = jest
        .spyOn(HTMLAnchorElement.prototype, 'click')
        .mockImplementation(() => {});
});

afterEach(() => {
    anchorClickSpy.mockRestore();
});

/* --------------------------------------------------------------------------- */
/* Suite.                                                                       */
/* --------------------------------------------------------------------------- */

describe('ReportsPage', () => {
    // Scenario 1 -----------------------------------------------------------
    it('renders the report-type select, date-range inputs, and CSV/PDF/Generate buttons', () => {
        RenderWithProviders(<ReportsPage />);

        expect(
            screen.getByRole('combobox', { name: REPORT_TYPE_LABEL }),
        ).toBeInTheDocument();
        expect(screen.getByLabelText(START_DATE_LABEL)).toBeInTheDocument();
        expect(screen.getByLabelText(END_DATE_LABEL)).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: GENERATE_BUTTON }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: CSV_BUTTON }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: PDF_BUTTON }),
        ).toBeInTheDocument();
    });

    // Scenario 2 -----------------------------------------------------------
    it('exposes Monthly/Yearly/Custom options bound to the ReportType enum values', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        await user.click(
            screen.getByRole('combobox', { name: REPORT_TYPE_LABEL }),
        );

        const monthlyOption = await screen.findByRole('option', {
            name: MONTHLY_OPTION,
        });
        const yearlyOption = screen.getByRole('option', {
            name: YEARLY_OPTION,
        });
        const customOption = screen.getByRole('option', {
            name: CUSTOM_OPTION,
        });

        // MUI stamps each MenuItem with `data-value` = its option value; asserting
        // against the enum members proves the options carry the enum VALUES.
        expect(monthlyOption).toHaveAttribute('data-value', ReportType.Monthly);
        expect(yearlyOption).toHaveAttribute('data-value', ReportType.Yearly);
        expect(customOption).toHaveAttribute('data-value', ReportType.Custom);
    });

    // Scenario 3 -----------------------------------------------------------
    it('disables the date inputs for Monthly/Yearly and enables them for Custom', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        // Default report type is Monthly -> dates auto-derived and disabled.
        expect(screen.getByLabelText(START_DATE_LABEL)).toBeDisabled();
        expect(screen.getByLabelText(END_DATE_LABEL)).toBeDisabled();

        // Custom -> both date inputs become editable.
        await SelectReportType(user, CUSTOM_OPTION);
        expect(screen.getByLabelText(START_DATE_LABEL)).toBeEnabled();
        expect(screen.getByLabelText(END_DATE_LABEL)).toBeEnabled();

        // Yearly -> disabled again (auto-derived).
        await SelectReportType(user, YEARLY_OPTION);
        expect(screen.getByLabelText(START_DATE_LABEL)).toBeDisabled();
        expect(screen.getByLabelText(END_DATE_LABEL)).toBeDisabled();
    });

    // Scenario 4 -----------------------------------------------------------
    it('rejects a Custom range where start is after end, then accepts start <= end', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        await SelectReportType(user, CUSTOM_OPTION);
        const startInput = screen.getByLabelText(START_DATE_LABEL);
        const endInput = screen.getByLabelText(END_DATE_LABEL);

        // start AFTER end -> validation blocks the run (no API call).
        fireEvent.change(startInput, { target: { value: '2026-12-31' } });
        fireEvent.change(endInput, { target: { value: '2026-01-01' } });
        await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent(VALIDATION_ORDER_MESSAGE);
        expect(mockGetTransactionReport).not.toHaveBeenCalled();

        // start <= end -> the request is issued with the typed range + confirm.
        fireEvent.change(startInput, { target: { value: '2026-01-01' } });
        fireEvent.change(endInput, { target: { value: '2026-12-31' } });
        mockGetTransactionReport.mockResolvedValueOnce(
            MakeReportResponse({ report_type: ReportType.Custom }),
        );
        await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));

        await waitFor(() => {
            expect(mockGetTransactionReport).toHaveBeenCalledWith(
                expect.objectContaining({
                    report_type: ReportType.Custom,
                    start_date: '2026-01-01',
                    end_date: '2026-12-31',
                    confirm: 'Y',
                }),
            );
        });
    });

    // Scenario 5 -----------------------------------------------------------
    it('generates the report and renders the returned rows in an on-screen table', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        const response = MakeReportResponse({
            rows: [
                MakeReportRow({ tran_id: 'TXN00000001', tran_amt: '-100.00' }),
                MakeReportRow({ tran_id: 'TXN00000002', tran_amt: '2500.75' }),
            ],
        });
        mockGetTransactionReport.mockResolvedValueOnce(response);

        // Default Monthly criteria are valid, so Generate calls the API directly.
        await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));

        await waitFor(() => {
            expect(mockGetTransactionReport).toHaveBeenCalledWith(
                expect.objectContaining({
                    report_type: ReportType.Monthly,
                    confirm: 'Y',
                }),
            );
        });

        // Rows render in a plain MUI <table> (NOT the shared DataTable); totals
        // live OUTSIDE the table, so within(table) isolates the row cells.
        const table = await screen.findByRole('table');
        expect(within(table).getByText('TXN00000001')).toBeInTheDocument();
        expect(within(table).getByText('TXN00000002')).toBeInTheDocument();
        // Amounts render through the shared FormatMoney (QA Issue 1 — the report
        // now uses the same '$' presentation as every other screen); the exact
        // decimal is preserved verbatim behind the prefix.
        expect(within(table).getByText('$-100.00')).toBeInTheDocument();
        expect(within(table).getByText('$2500.75')).toBeInTheDocument();
    });

    // Scenario 6 -----------------------------------------------------------
    it('downloads the report as CSV via DownloadTransactionReport(req, "csv")', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        // A report must exist before the download buttons enable.
        await GenerateReport(user, MakeReportResponse());

        const csvBlob = new Blob(['csv'], { type: 'text/csv' });
        mockDownloadTransactionReport.mockResolvedValueOnce(csvBlob);

        const csvButton = screen.getByRole('button', { name: CSV_BUTTON });
        await waitFor(() => expect(csvButton).toBeEnabled());
        await user.click(csvButton);

        await waitFor(() => {
            expect(mockDownloadTransactionReport).toHaveBeenCalledWith(
                expect.objectContaining({ confirm: 'Y' }),
                'csv',
            );
            expect(createObjectUrlMock).toHaveBeenCalled();
        });
        expect(anchorClickSpy).toHaveBeenCalled();
    });

    // Scenario 7 -----------------------------------------------------------
    it('downloads the report as PDF via DownloadTransactionReport(req, "pdf")', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        await GenerateReport(user, MakeReportResponse());

        const pdfBlob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
        mockDownloadTransactionReport.mockResolvedValueOnce(pdfBlob);

        const pdfButton = screen.getByRole('button', { name: PDF_BUTTON });
        await waitFor(() => expect(pdfButton).toBeEnabled());
        await user.click(pdfButton);

        await waitFor(() => {
            expect(mockDownloadTransactionReport).toHaveBeenCalledWith(
                expect.objectContaining({ confirm: 'Y' }),
                'pdf',
            );
            expect(createObjectUrlMock).toHaveBeenCalled();
        });
        expect(anchorClickSpy).toHaveBeenCalled();
    });

    // Scenario 8 -----------------------------------------------------------
    it('renders totals and row amounts verbatim as Decimal strings', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        // Trailing-zero / signed values that Number()/toFixed() would alter,
        // proving the strings are rendered verbatim (AAP Section 0.7.1).
        const response = MakeReportResponse({
            rows: [MakeReportRow({ tran_id: 'TXN00000009', tran_amt: '-100.00' })],
            page_total: '0.10',
            account_total: '1000.00',
            grand_total: '-9999.90',
        });
        mockGetTransactionReport.mockResolvedValueOnce(response);

        await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));
        const table = await screen.findByRole('table');

        // Row amount inside the table (would be '-100' if Number-coerced); the
        // trailing-zero decimal is preserved verbatim behind the shared '$'
        // prefix added by FormatMoney (QA Issue 1).
        expect(within(table).getByText('$-100.00')).toBeInTheDocument();

        // Running totals outside the table (would drop trailing zeros if coerced),
        // each displayed through FormatMoney so the '$' presentation is consistent.
        expect(screen.getByText(/Page Total:\s*\$0\.10/)).toBeInTheDocument();
        expect(
            screen.getByText(/Account Total:\s*\$1000\.00/),
        ).toBeInTheDocument();
        expect(
            screen.getByText(/Grand Total:\s*\$-9999\.90/),
        ).toBeInTheDocument();
    });

    // Scenario 9 -----------------------------------------------------------
    it('surfaces a thrown ApiError in an error alert', async () => {
        const user = SetupUser();
        RenderWithProviders(<ReportsPage />);

        // The real ApiError class (kept via requireActual) drives the alert path.
        mockGetTransactionReport.mockRejectedValueOnce(
            new ApiError({ status: 500, message: 'Report generation failed.' }),
        );

        await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent('Report generation failed.');
        expect(mockGetTransactionReport).toHaveBeenCalledWith(
            expect.objectContaining({ confirm: 'Y' }),
        );
    });

    it('invalidates an in-flight report request when the page unmounts (M-05)', async () => {
        const user = SetupUser();

        // A hand-controlled report request left in flight across unmount. The
        // Generate button disables while loading, so a second overlapping fetch
        // is not reachable via the UI; the realistic report race is therefore
        // unmount-during-load, which the request-generation guard covers.
        let resolveReport!: (value: ReportResponse) => void;
        const pendingReport = new Promise<ReportResponse>((resolve) => {
            resolveReport = resolve;
        });
        mockGetTransactionReport.mockReturnValueOnce(pendingReport);

        const { unmount } = RenderWithProviders(<ReportsPage />);

        // Default Monthly criteria are valid, so Generate fires the request.
        await user.click(screen.getByRole('button', { name: GENERATE_BUTTON }));
        await waitFor(() =>
            expect(mockGetTransactionReport).toHaveBeenCalledTimes(1),
        );

        // Unmount BEFORE the request resolves; the effect cleanup bumps the
        // request generation, invalidating the in-flight request (QA M-05).
        unmount();

        // Resolving now runs the guarded continuation, whose captured generation
        // no longer matches, so it discards the result — a safe no-op with no
        // state update on the torn-down tree and no throw.
        resolveReport(MakeReportResponse());
        await waitFor(() =>
            expect(mockGetTransactionReport).toHaveBeenCalledTimes(1),
        );
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });
});
