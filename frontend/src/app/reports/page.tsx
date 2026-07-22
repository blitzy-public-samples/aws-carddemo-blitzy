'use client';

/*
 * Transaction Reports page — Next.js App Router route `/reports`.
 *
 * Legacy lineage (REFERENCE only — never modified):
 *   - BMS map:       app/bms/CORPT00.bms      (map CORPT0A, 24x80)
 *   - Symbolic map:  app/cpy-bms/CORPT00.CPY  (CORPT0AI)
 *   - CICS Tx:       CR00
 *   - COBOL program: CORPT00C
 *
 * Authorized behavior change (AAP §0.7 / §0.8.4 — the single intentional
 * behavior-adjacent change in the whole migration): the legacy program submitted
 * report output to TDQ / GDG datasets as text + HTML. The modern redesign
 * replaces that output path with an on-screen Material UI table PLUS CSV and PDF
 * downloads. The user selects a report type (Monthly / Yearly / Custom) and a
 * date range, clicks Generate to fetch the report, views the rows in a table,
 * and can download the same report as CSV or PDF.
 *
 * The legacy CONFIRM (Y/N) prompt is preserved as `confirm='Y'` sent on Generate:
 * clicking Generate IS the confirmation — there is intentionally no separate
 * confirm dialog (authorized redesign).
 *
 * This page renders as the `{children}` of the shared root layout
 * (`src/app/layout.tsx`), which already provides AppRouterCacheProvider →
 * ThemeProvider → CssBaseline → AppShell. It therefore never re-wraps providers
 * or the app shell.
 */

import { useState, useEffect } from 'react';

import { ReportType } from '@/types';
import type {
    ReportRequest,
    ReportResponse,
    TransactionReportRow,
} from '@/types';

import { ReportsApi } from '@/lib/apiClient';

import { FormField } from '@/components/FormField';
import type { FieldOption } from '@/components/FormField';
import { ErrorAlert } from '@/components/ErrorAlert';

import {
    Container,
    Paper,
    Stack,
    Box,
    Typography,
    Button,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    CircularProgress,
} from '@mui/material';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs Test Rule: ALL_UPPERCASE with underscores).        */
/* ------------------------------------------------------------------------- */

/** Page heading — the legacy CORPT00 screen title text, preserved verbatim. */
const PAGE_TITLE = 'Transaction Reports';

/** Short instructional subtitle beneath the heading. */
const PAGE_SUBTITLE =
    'Select a report type and date range, then generate the report.';

/**
 * Report-type dropdown options. Values come from the runtime {@link ReportType}
 * enum; labels are the exact legacy CORPT00 option captions.
 */
const REPORT_TYPE_OPTIONS: FieldOption[] = [
    { value: ReportType.Monthly, label: 'Monthly (Current Month)' },
    { value: ReportType.Yearly, label: 'Yearly (Current Year)' },
    { value: ReportType.Custom, label: 'Custom (Date Range)' },
];

/** The full set of valid report types (used for defensive validation). */
const VALID_REPORT_TYPES: ReportType[] = [
    ReportType.Monthly,
    ReportType.Yearly,
    ReportType.Custom,
];

/** Column shape for the results table, keyed by a {@link TransactionReportRow} field. */
interface ReportColumn {
    key: keyof TransactionReportRow;
    header: string;
    numeric?: boolean;
}

/** Results-table column definitions (order mirrors the legacy detail line). */
const REPORT_COLUMNS: ReportColumn[] = [
    { key: 'tran_id', header: 'Transaction ID' },
    { key: 'acct_id', header: 'Account' },
    { key: 'tran_type_cd', header: 'Type' },
    { key: 'tran_type_desc', header: 'Type Desc' },
    { key: 'tran_cat_cd', header: 'Category' },
    { key: 'tran_cat_desc', header: 'Category Desc' },
    { key: 'tran_source', header: 'Source' },
    { key: 'tran_amt', header: 'Amount', numeric: true },
];

/** FormField `name` keys — also the routing keys used in HandleFieldChange. */
const REPORT_TYPE_FIELD = 'reportType';
const START_DATE_FIELD = 'startDate';
const END_DATE_FIELD = 'endDate';

/** Download format tokens passed to ReportsApi.DownloadTransactionReport. */
const CSV_FORMAT = 'csv' as const;
const PDF_FORMAT = 'pdf' as const;

/**
 * Legacy CONFIRM (Y/N) value. In the redesign clicking Generate IS the
 * confirmation, so 'Y' is always sent — preserving the CORPT00 CONFIRM semantics.
 */
const CONFIRM_YES = 'Y';

/** Filename stem for downloads when a concrete date range is available. */
const DOWNLOAD_FILENAME_STEM = 'transaction-report';

/** Message shown in the results table when the backend returns zero rows. */
const NO_ROWS_MESSAGE = 'No transactions found for the selected criteria.';

/* ------------------------------------------------------------------------- */
/* Pure module-level helpers (no React state; defined once, outside render).  */
/* ------------------------------------------------------------------------- */

/**
 * Formats a Date as an ISO `YYYY-MM-DD` string with zero-padding. Pure string
 * formatting with no locale dependence (avoids `toLocaleDateString` drift).
 *
 * @param date - The date to format.
 * @returns The ISO date string, e.g. `2026-07-01`.
 */
function FormatIsoDate(date: Date): string {
    const year = date.getFullYear();
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    return `${year}-${month}-${day}`;
}

/**
 * Derives the start/end ISO date range for a report type: Monthly → the first
 * and last day of the current month; Yearly → Jan 1 to Dec 31 of the current
 * year; Custom → empty strings (the caller keeps whatever the user typed).
 *
 * `new Date()` is read here (from an effect or a handler), never during render,
 * to avoid a Next.js SSR/client hydration mismatch.
 *
 * @param selectedType - The chosen report type.
 * @returns The `{ start, end }` ISO date range.
 */
function DeriveDateRange(selectedType: ReportType): {
    start: string;
    end: string;
} {
    const today = new Date();
    if (selectedType === ReportType.Monthly) {
        const firstDay = new Date(today.getFullYear(), today.getMonth(), 1);
        const lastDay = new Date(today.getFullYear(), today.getMonth() + 1, 0);
        return { start: FormatIsoDate(firstDay), end: FormatIsoDate(lastDay) };
    }
    if (selectedType === ReportType.Yearly) {
        const firstDay = new Date(today.getFullYear(), 0, 1);
        const lastDay = new Date(today.getFullYear(), 11, 31);
        return { start: FormatIsoDate(firstDay), end: FormatIsoDate(lastDay) };
    }
    return { start: '', end: '' };
}

/**
 * Triggers a browser file download for a Blob using the standard idiom:
 * `URL.createObjectURL` + a transient anchor element that is clicked and removed.
 *
 * NOTE — DOM plumbing, not rendered UI: the `<a>` element here is created
 * imperatively as a browser download API side-effect; it is never part of the
 * React tree, so it does NOT violate the "MUI components over raw HTML"
 * design-system rule (which governs the JSX this page renders). All
 * `window`/`document` access is guarded with `typeof window !== 'undefined'` for
 * SSR safety.
 *
 * @param blob - The binary report payload returned by the API.
 * @param filename - The suggested download filename.
 */
function TriggerBlobDownload(blob: Blob, filename: string): void {
    if (typeof window === 'undefined') {
        return;
    }
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    window.URL.revokeObjectURL(objectUrl);
}

/* ------------------------------------------------------------------------- */
/* Page component.                                                           */
/* ------------------------------------------------------------------------- */

/**
 * Transaction Reports page. Renders the report-criteria form (type + date
 * range), fetches the report on Generate, shows the rows in an on-screen table
 * with running totals, and offers CSV / PDF downloads. Modern redesign of the
 * legacy CORPT00 screen (CICS CR00 / COBOL CORPT00C).
 *
 * @returns The reports page element.
 */
export default function ReportsPage() {
    const [reportType, setReportType] = useState<ReportType>(
        ReportType.Monthly,
    );
    const [startDate, setStartDate] = useState<string>('');
    const [endDate, setEndDate] = useState<string>('');
    const [reportData, setReportData] = useState<ReportResponse | null>(null);
    const [loading, setLoading] = useState<boolean>(false);
    const [errorOpen, setErrorOpen] = useState<boolean>(false);
    const [errorValue, setErrorValue] = useState<unknown>(null);

    // Custom mode enables the date fields; Monthly/Yearly derive + disable them.
    const isCustom = reportType === ReportType.Custom;

    /**
     * Seeds the initial Monthly date range on mount. Done in an effect (not
     * during render) so `new Date()` runs only on the client, avoiding a Next.js
     * SSR/client hydration mismatch.
     */
    useEffect(() => {
        const range = DeriveDateRange(ReportType.Monthly);
        setStartDate(range.start);
        setEndDate(range.end);
    }, []);

    /**
     * Applies a newly selected report type: stores it, then for Monthly/Yearly
     * derives and fills the date range (the fields are then disabled). Custom
     * leaves the user's typed dates untouched.
     *
     * @param selectedType - The report type chosen in the Select.
     */
    function ApplyReportType(selectedType: ReportType): void {
        setReportType(selectedType);
        if (selectedType !== ReportType.Custom) {
            const range = DeriveDateRange(selectedType);
            setStartDate(range.start);
            setEndDate(range.end);
        }
    }

    /**
     * Single change handler for every FormField (FormField invokes
     * `onChange(name, value)`). Routes the change to the matching state update.
     *
     * @param fieldName - The field's `name` prop.
     * @param value - The new string value.
     */
    function HandleFieldChange(fieldName: string, value: string): void {
        if (fieldName === REPORT_TYPE_FIELD) {
            ApplyReportType(value as ReportType);
            return;
        }
        if (fieldName === START_DATE_FIELD) {
            setStartDate(value);
            return;
        }
        if (fieldName === END_DATE_FIELD) {
            setEndDate(value);
        }
    }

    /**
     * Builds the report request from the current criteria. `confirm: 'Y'`
     * preserves the legacy CORPT00 CONFIRM prompt — clicking Generate IS the
     * confirmation in the redesign, so there is no separate confirm dialog.
     *
     * @returns The assembled {@link ReportRequest}.
     */
    function BuildReportRequest(): ReportRequest {
        return {
            report_type: reportType,
            start_date: startDate,
            end_date: endDate,
            confirm: CONFIRM_YES,
        };
    }

    /**
     * Validates the report criteria before any API call (mirrors the legacy
     * BMS/PROCEDURE edits, AAP §0.7.8). ISO `YYYY-MM-DD` strings compare
     * lexicographically, so a plain string comparison enforces start <= end.
     *
     * @param request - The request to validate.
     * @returns A user-facing error message, or `null` when the request is valid.
     */
    function ValidateReportRequest(request: ReportRequest): string | null {
        if (!VALID_REPORT_TYPES.includes(request.report_type)) {
            return 'Please select a valid report type.';
        }
        if (request.report_type === ReportType.Custom) {
            if (!request.start_date || !request.end_date) {
                return 'Start date and end date are both required for a custom range.';
            }
            if (request.start_date > request.end_date) {
                return 'Start date must be on or before the end date.';
            }
        }
        return null;
    }

    /**
     * Generates the report: validates the criteria, then fetches it. Validation
     * failures surface in ErrorAlert WITHOUT calling the API; a thrown ApiError
     * is passed straight to ErrorAlert (specific catch — never swallowed).
     */
    async function HandleGenerate(): Promise<void> {
        const request = BuildReportRequest();
        const validationError = ValidateReportRequest(request);
        if (validationError) {
            setErrorValue(validationError);
            setErrorOpen(true);
            return;
        }
        setLoading(true);
        try {
            const data = await ReportsApi.GetTransactionReport(request);
            setReportData(data);
        } catch (caughtError) {
            setErrorValue(caughtError);
            setErrorOpen(true);
        } finally {
            setLoading(false);
        }
    }

    /**
     * Shared download routine for both formats: builds and validates the request,
     * fetches the report Blob, and triggers a browser download. Errors surface in
     * ErrorAlert. Extracted so the two public handlers stay tiny (Ochs).
     *
     * @param format - The download format ('csv' or 'pdf').
     */
    async function DownloadReport(format: 'csv' | 'pdf'): Promise<void> {
        const request = BuildReportRequest();
        const validationError = ValidateReportRequest(request);
        if (validationError) {
            setErrorValue(validationError);
            setErrorOpen(true);
            return;
        }
        try {
            const blob = await ReportsApi.DownloadTransactionReport(
                request,
                format,
            );
            TriggerBlobDownload(blob, BuildDownloadFilename(format));
        } catch (caughtError) {
            setErrorValue(caughtError);
            setErrorOpen(true);
        }
    }

    /** Downloads the current report criteria as a CSV file. */
    async function HandleDownloadCsv(): Promise<void> {
        await DownloadReport(CSV_FORMAT);
    }

    /** Downloads the current report criteria as a PDF file. */
    async function HandleDownloadPdf(): Promise<void> {
        await DownloadReport(PDF_FORMAT);
    }

    /**
     * Builds a descriptive download filename from the current date range, falling
     * back to a generic stem when the range is empty.
     *
     * @param format - The file extension to append ('csv' or 'pdf').
     * @returns The suggested download filename.
     */
    function BuildDownloadFilename(format: string): string {
        if (startDate && endDate) {
            return `${DOWNLOAD_FILENAME_STEM}-${startDate}_to_${endDate}.${format}`;
        }
        return `${DOWNLOAD_FILENAME_STEM}.${format}`;
    }

    /** Closes the error alert and clears the held error value. */
    function HandleErrorClose(): void {
        setErrorOpen(false);
        setErrorValue(null);
    }

    /**
     * Renders the table body: a single "no rows" message row when the report is
     * empty, otherwise one row per transaction. `tran_amt` is rendered verbatim
     * as a Decimal string — never coerced to a number (float rounding would be a
     * compliance failure, AAP §0.7.1).
     *
     * @returns The table-body row element(s).
     */
    function RenderResultRows() {
        if (!reportData || reportData.rows.length === 0) {
            return (
                <TableRow>
                    <TableCell
                        colSpan={REPORT_COLUMNS.length}
                        align="center"
                    >
                        <Typography variant="body2">
                            {NO_ROWS_MESSAGE}
                        </Typography>
                    </TableCell>
                </TableRow>
            );
        }
        return reportData.rows.map((row, index) => (
            <TableRow key={`${row.tran_id}-${index}`}>
                {REPORT_COLUMNS.map((column) => (
                    <TableCell
                        key={column.key}
                        align={column.numeric ? 'right' : 'left'}
                    >
                        {row[column.key]}
                    </TableCell>
                ))}
            </TableRow>
        ));
    }

    /**
     * Renders the running totals beneath the table. All totals are Decimal
     * strings rendered verbatim (never coerced to a number, AAP §0.7.1).
     *
     * @returns The totals element, or `null` when there is no report.
     */
    function RenderTotals() {
        if (!reportData) {
            return null;
        }
        return (
            <Box sx={{ p: 2 }}>
                <Stack spacing={0.5} sx={{ alignItems: 'flex-end' }}>
                    <Typography variant="body2">
                        Page Total: {reportData.page_total}
                    </Typography>
                    <Typography variant="body2">
                        Account Total: {reportData.account_total}
                    </Typography>
                    <Typography variant="subtitle2">
                        Grand Total: {reportData.grand_total}
                    </Typography>
                </Stack>
            </Box>
        );
    }

    /**
     * Renders the results table (header, body rows, and totals) once a report has
     * been generated. Returns `null` before the first Generate.
     *
     * @returns The results table element, or `null` when there is no report yet.
     */
    function RenderResultsTable() {
        if (!reportData) {
            return null;
        }
        return (
            <TableContainer component={Paper} sx={{ mt: 3 }}>
                <Table size="small" aria-label={PAGE_TITLE}>
                    <TableHead>
                        <TableRow>
                            {REPORT_COLUMNS.map((column) => (
                                <TableCell
                                    key={column.key}
                                    align={column.numeric ? 'right' : 'left'}
                                >
                                    {column.header}
                                </TableCell>
                            ))}
                        </TableRow>
                    </TableHead>
                    <TableBody>{RenderResultRows()}</TableBody>
                </Table>
                {RenderTotals()}
            </TableContainer>
        );
    }

    return (
        <Container maxWidth="lg" sx={{ py: 3 }}>
            <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
                {PAGE_TITLE}
            </Typography>
            <Typography
                variant="body2"
                color="text.secondary"
                sx={{ mb: 2 }}
            >
                {PAGE_SUBTITLE}
            </Typography>

            <Paper sx={{ p: 3 }}>
                <Stack spacing={2}>
                    <FormField
                        name={REPORT_TYPE_FIELD}
                        label="Report Type"
                        type="select"
                        value={reportType}
                        options={REPORT_TYPE_OPTIONS}
                        onChange={HandleFieldChange}
                        required
                    />
                    <FormField
                        name={START_DATE_FIELD}
                        label="Start Date"
                        type="date"
                        value={startDate}
                        onChange={HandleFieldChange}
                        disabled={!isCustom}
                        required={isCustom}
                    />
                    <FormField
                        name={END_DATE_FIELD}
                        label="End Date"
                        type="date"
                        value={endDate}
                        onChange={HandleFieldChange}
                        disabled={!isCustom}
                        required={isCustom}
                    />
                    {/*
                      * Action row. On narrow viewports (xs) the three action
                      * buttons stack vertically so they never force horizontal
                      * page overflow (QA #3 — reports button row no-wrap at
                      * 375px). From the `sm` breakpoint up they sit in a row,
                      * and `flexWrap: 'wrap'` lets them wrap onto a second line
                      * rather than overflow if the container is narrow.
                      */}
                    <Stack
                        direction={{ xs: 'column', sm: 'row' }}
                        spacing={2}
                        sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                    >
                        <Button
                            variant="contained"
                            color="primary"
                            onClick={HandleGenerate}
                            disabled={loading}
                        >
                            Generate
                        </Button>
                        <Button
                            variant="outlined"
                            onClick={HandleDownloadCsv}
                            disabled={loading || !reportData}
                        >
                            Download CSV
                        </Button>
                        <Button
                            variant="outlined"
                            onClick={HandleDownloadPdf}
                            disabled={loading || !reportData}
                        >
                            Download PDF
                        </Button>
                        {loading ? <CircularProgress /> : null}
                    </Stack>
                </Stack>
            </Paper>

            {RenderResultsTable()}

            <ErrorAlert
                open={errorOpen}
                onClose={HandleErrorClose}
                error={errorValue}
            />
        </Container>
    );
}
