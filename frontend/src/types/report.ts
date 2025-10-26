/**
 * TypeScript type definitions for report generation
 * Converted from COBOL program: CORPT00C.cbl
 * 
 * Original function: Print transaction reports by submitting batch job from online
 * Conversion notes:
 * - COBOL MONTHLYI/YEARLYI/CUSTOMI fields converted to ReportType enum
 * - COBOL WS-START-DATE/WS-END-DATE (YYYY-MM-DD format) converted to string
 * - COBOL CONFIRMI field converted to confirmation in request flow
 * - Date validation from CSUTLDTC utility replicated in frontend validation
 * 
 * @module types/report
 */

/**
 * Report type enumeration
 * Maps COBOL report options from CORPT00C.cbl
 * 
 * COBOL source:
 * - MONTHLYI: Monthly report (lines 213-238)
 * - YEARLYI: Yearly report (lines 239-255)
 * - CUSTOMI: Custom date range report (lines 256-436)
 */
export enum ReportType {
  /**
   * Monthly report for current month
   * Generates report from first day to last day of current month
   * Date range automatically calculated from current date
   */
  MONTHLY = 'MONTHLY',
  
  /**
   * Yearly report for current year
   * Generates report from January 1 to December 31 of current year
   * Date range automatically calculated from current date
   */
  YEARLY = 'YEARLY',
  
  /**
   * Custom date range report
   * User specifies start and end dates
   * Requires date range input and validation
   */
  CUSTOM = 'CUSTOM'
}

/**
 * Report export format enumeration
 * Supported output formats for generated reports
 * 
 * Backend supports multiple export formats for report download
 */
export enum ReportFormat {
  /**
   * Portable Document Format
   * Best for printable reports with formatting
   * Default format if not specified
   */
  PDF = 'PDF',
  
  /**
   * Comma-Separated Values
   * Best for data export and spreadsheet import
   * Plain text format with comma delimiters
   */
  CSV = 'CSV',
  
  /**
   * Microsoft Excel format
   * Best for spreadsheet analysis with formatting
   * Binary format (.xlsx)
   */
  EXCEL = 'EXCEL'
}

/**
 * Report menu item for display in ReportMenuPage
 * Represents available report options in the report selection menu
 * 
 * Used by ReportMenuPage component to render menu of available reports
 */
export interface ReportMenuItem {
  /**
   * Report type identifier
   * Determines which report logic to execute
   */
  reportType: ReportType;
  
  /**
   * Display label for menu item
   * Human-readable name shown in UI
   * Example: "Monthly Transaction Report"
   */
  label: string;
  
  /**
   * Detailed description of report content and purpose
   * Explains what data is included and timeframe
   * Example: "Generates report for all transactions in the current month"
   */
  description: string;
  
  /**
   * Whether this report type requires date range input
   * - true for CUSTOM reports (user must specify dates)
   * - false for MONTHLY and YEARLY reports (dates auto-calculated)
   */
  requiresDateRange: boolean;
}

/**
 * Report generation request for POST /api/reports/generate
 * Matches backend ReportGenerationRequest DTO
 * 
 * COBOL source mapping:
 * - reportType → MONTHLYI/YEARLYI/CUSTOMI field selection
 * - startDate → WS-START-DATE (YYYY-MM-DD format, line 60-65)
 * - endDate → WS-END-DATE (YYYY-MM-DD format, line 66-71)
 * - format → Output format selection (not in COBOL, added for modern API)
 * 
 * Date validation requirements from CORPT00C.cbl:
 * - Custom reports require both startDate and endDate (lines 259-427)
 * - Start date must be before or equal to end date
 * - Month must be 1-12 (lines 329-336, 355-362)
 * - Day must be 1-31 (lines 338-345, 364-371)
 * - Year must be numeric (lines 347-353, 373-379)
 * - Dates validated by CSUTLDTC utility (lines 388-426)
 */
export interface ReportGenerationRequest {
  /**
   * Report type to generate
   * Required field - determines report logic and date range handling
   */
  reportType: ReportType;
  
  /**
   * Start date for report in YYYY-MM-DD format
   * Required for CUSTOM report type
   * Optional for MONTHLY and YEARLY (auto-calculated)
   * 
   * COBOL: WS-START-DATE (line 60-65)
   * Format: YYYY-MM-DD per WS-DATE-FORMAT (line 72)
   * 
   * Example: "2024-01-01"
   */
  startDate?: string;
  
  /**
   * End date for report in YYYY-MM-DD format
   * Required for CUSTOM report type
   * Optional for MONTHLY and YEARLY (auto-calculated)
   * 
   * COBOL: WS-END-DATE (line 66-71)
   * Format: YYYY-MM-DD per WS-DATE-FORMAT (line 72)
   * 
   * Example: "2024-12-31"
   */
  endDate?: string;
  
  /**
   * Optional account ID filter for account-specific reports
   * If provided, report includes only transactions for this account
   * If omitted, report includes all accounts
   * 
   * Not present in COBOL (enhancement for modern API)
   */
  accountId?: number;
  
  /**
   * Desired output format
   * Defaults to PDF if not specified
   * 
   * Not present in COBOL (enhancement for modern API)
   * COBOL generates fixed-format printed reports via JCL
   */
  format?: ReportFormat;
}

/**
 * Report generation response from POST /api/reports/generate
 * Contains download URL and metadata for generated report
 * 
 * Backend generates report file and returns download link
 * Report files expire after 24 hours for security
 */
export interface ReportGenerationResponse {
  /**
   * Unique report generation identifier
   * UUID format string for tracking report job
   * Used to query report status or retrieve report later
   * 
   * Example: "550e8400-e29b-41d4-a716-446655440000"
   */
  reportId: string;
  
  /**
   * Download URL for generated report file
   * Temporary signed URL for secure file download
   * URL expires after 24 hours
   * 
   * Example: "/api/reports/download/550e8400-e29b-41d4-a716-446655440000"
   */
  downloadUrl: string;
  
  /**
   * Report generation timestamp
   * ISO 8601 format timestamp
   * 
   * Example: "2024-01-15T10:30:00Z"
   */
  generatedAt: string;
  
  /**
   * URL expiration timestamp
   * ISO 8601 format timestamp
   * Reports auto-delete after 24 hours for security
   * 
   * Example: "2024-01-16T10:30:00Z"
   */
  expiresAt: string;
  
  /**
   * Report generation status
   * Tracks progress of potentially long-running report job
   * 
   * - COMPLETED: Report ready for download
   * - PROCESSING: Report generation in progress
   * - FAILED: Report generation failed (check error message)
   */
  status: 'COMPLETED' | 'PROCESSING' | 'FAILED';
  
  /**
   * File size in bytes
   * Optional - only present when status is COMPLETED
   * Used to show file size in UI before download
   * 
   * Example: 1048576 (1 MB)
   */
  fileSizeBytes?: number;
  
  /**
   * Original file name with extension
   * Suggested filename for download
   * Includes appropriate extension based on format (.pdf, .csv, .xlsx)
   * 
   * Example: "transaction_report_2024-01.pdf"
   */
  fileName: string;
}

/**
 * Report generation status
 * Tracks progress of long-running report jobs
 * 
 * Status lifecycle:
 * QUEUED → PROCESSING → COMPLETED (success path)
 * QUEUED → PROCESSING → FAILED (error path)
 * COMPLETED → EXPIRED (after 24 hours)
 */
export type ReportStatus = 
  | 'QUEUED'      // Report job submitted, waiting to start
  | 'PROCESSING'  // Report generation in progress
  | 'COMPLETED'   // Report ready for download
  | 'FAILED'      // Report generation failed
  | 'EXPIRED';    // Report download URL expired (>24 hours old)

/**
 * Default report menu items
 * Predefined list of available reports matching COBOL report options
 * 
 * Maps to COBOL CORPT00C.cbl report selection screen:
 * - Monthly report option (line 213-238)
 * - Yearly report option (line 239-255)
 * - Custom date range option (line 256-436)
 */
export const DEFAULT_REPORT_MENU_ITEMS: ReportMenuItem[] = [
  {
    reportType: ReportType.MONTHLY,
    label: 'Monthly Transaction Report',
    description: 'Generates report for all transactions in the current month (first day to last day)',
    requiresDateRange: false
  },
  {
    reportType: ReportType.YEARLY,
    label: 'Yearly Transaction Report',
    description: 'Generates report for all transactions in the current year (January 1 to December 31)',
    requiresDateRange: false
  },
  {
    reportType: ReportType.CUSTOM,
    label: 'Custom Date Range Report',
    description: 'Generates report for transactions within a user-specified date range',
    requiresDateRange: true
  }
];
