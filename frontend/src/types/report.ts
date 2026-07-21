/**
 * Transaction-report DTOs.
 * Mirrors `backend/app/schemas/report.py`.
 * References: app/cpy-bms/CORPT00.CPY (report request screen),
 * app/cpy/CVTRA07Y.cpy (detail-row + totals layout), app/cpy/CVTRA05Y.cpy (tran record).
 * Amount/total fields are `string` (Decimal) - never a number.
 * Legacy TDQ/GDG text+HTML output is redesigned to on-screen table + CSV/PDF (Section 0.8.4).
 */

/** Report kind selector (CORPT00). String values match the wire contract. */
export enum ReportType {
    Monthly = 'Monthly',
    Yearly = 'Yearly',
    Custom = 'Custom',
}

/** Report request. start_date/end_date are ISO dates; confirm is 'Y'/'N'. */
export interface ReportRequest {
    report_type: ReportType;
    start_date: string;
    end_date: string;
    confirm?: string;
}

/** A single detail row (CVTRA07Y). Description widths truncated: type 15, cat 29. */
export interface TransactionReportRow {
    tran_id: string;         // X(16)
    acct_id: string;         // X(11)
    tran_type_cd: string;    // X(2)
    tran_type_desc: string;  // X(15) - report truncates to 15
    tran_cat_cd: string;     // 9(4)
    tran_cat_desc: string;   // X(29) - report truncates to 29
    tran_source: string;     // X(10)
    tran_amt: string;        // Decimal as string
}

/** Report payload: rows + running totals (all Decimal strings). */
export interface ReportResponse {
    report_type: ReportType;
    start_date: string;
    end_date: string;
    rows: TransactionReportRow[];
    page_total: string;
    account_total: string;
    grand_total: string;
    report_name?: string;
}
